package com.onelake.catalog.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.onelake.catalog.config.TrinoConnectionFactory;
import com.onelake.catalog.domain.entity.Asset;
import com.onelake.catalog.dto.SchemaChangeExecutionResultDTO;
import com.onelake.catalog.repository.AssetRepository;
import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class CatalogSchemaChangeService {

    private static final Pattern IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]{0,127}");
    private static final Pattern DECIMAL = Pattern.compile("(?i)^DECIMAL\\((\\d{1,2}),(\\d{1,2})\\)$");
    private static final Set<String> CLASSIFICATIONS = Set.of("L1", "L2", "L3", "L4");

    private final AssetRepository assetRepo;
    private final TrinoConnectionFactory trinoConnectionFactory;
    private final JdbcTemplate jdbc;

    public SchemaChangeExecutionResultDTO executeApproved(UUID approvalId) {
        UUID tenantId = requireTenant();
        ApprovalSnapshot approval = loadApproval(tenantId, approvalId);
        if (!"SCHEMA_CHANGE".equals(approval.requestType())) {
            throw new BizException(40063, "审批单不是 Schema 变更申请");
        }
        if (!"APPROVED".equals(approval.status())) {
            throw new BizException(40963, "Schema 变更审批未通过，不能执行");
        }
        if ("SUCCEEDED".equals(text(approval.payload().get("executionStatus")).toUpperCase(Locale.ROOT))) {
            return new SchemaChangeExecutionResultDTO(
                approvalId,
                approval.targetRef(),
                text(approval.payload().get("changeType")),
                "SUCCEEDED",
                text(approval.payload().get("executionSql")),
                "Schema 变更已执行，无需重复提交",
                parseInstant(approval.payload().get("executedAt"))
            );
        }

        Asset asset = assetRepo.findByTenantIdAndOmFqn(tenantId, approval.targetRef())
            .orElseThrow(() -> new BizException(40400, "资产不存在: " + approval.targetRef()));
        ChangePlan plan = buildPlan(asset, approval.payload());

        try (Connection connection = trinoConnectionFactory.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(plan.sql());
        } catch (SQLException e) {
            markExecution(approvalId, approval.payload(), "FAILED", plan.sql(), rootMessage(e));
            throw new BizException(50063, "Schema 变更执行失败: " + rootMessage(e), e);
        }

        asset.setColumns(JsonUtil.toJson(plan.columns()));
        asset.setClassification(maxClassification(plan.columns()));
        asset.setSyncedAt(Instant.now());
        assetRepo.save(asset);
        Instant executedAt = Instant.now();
        markExecution(approvalId, approval.payload(), "SUCCEEDED", plan.sql(), null, executedAt);
        return new SchemaChangeExecutionResultDTO(
            approvalId,
            asset.getOmFqn(),
            plan.changeType(),
            "SUCCEEDED",
            plan.sql(),
            "Schema 变更已执行并回写 Catalog",
            executedAt
        );
    }

    private ChangePlan buildPlan(Asset asset, Map<String, Object> payload) {
        String changeType = required(payload, "changeType").toUpperCase(Locale.ROOT);
        TableRef table = tableRef(asset.getOmFqn());
        List<Map<String, Object>> columns = parseColumns(asset.getColumns());
        String target = "iceberg.%s.%s".formatted(quote(table.schema()), quote(table.table()));

        return switch (changeType) {
            case "ADD_COLUMN" -> addColumnPlan(target, columns, payload);
            case "DROP_COLUMN" -> dropColumnPlan(target, columns, payload);
            case "RENAME_COLUMN" -> renameColumnPlan(target, columns, payload);
            case "CHANGE_TYPE" -> changeTypePlan(target, columns, payload);
            default -> throw new BizException(40063, "Schema 变更类型不支持: " + changeType);
        };
    }

    private ChangePlan addColumnPlan(String target, List<Map<String, Object>> columns, Map<String, Object> payload) {
        String name = identifier(required(payload, "columnName"), "字段名不合法");
        String type = trinoType(required(payload, "dataType"));
        if (findColumn(columns, name).isPresent()) {
            throw new BizException(40963, "字段已存在: " + name);
        }
        Map<String, Object> column = new LinkedHashMap<>();
        column.put("name", name);
        column.put("type", type);
        Object description = payload.get("description");
        if (description != null && !text(description).isBlank()) {
            column.put("description", text(description));
        }
        String classification = normalizeClassification(text(payload.get("classification")));
        if (classification != null) {
            column.put("classification", classification);
        }
        List<Map<String, Object>> next = new ArrayList<>(columns);
        next.add(column);
        return new ChangePlan("ADD_COLUMN", "ALTER TABLE " + target + " ADD COLUMN " + quote(name) + " " + type, next);
    }

    private ChangePlan dropColumnPlan(String target, List<Map<String, Object>> columns, Map<String, Object> payload) {
        String name = identifier(required(payload, "columnName"), "字段名不合法");
        if (findColumn(columns, name).isEmpty()) {
            throw new BizException(40463, "字段不存在: " + name);
        }
        List<Map<String, Object>> next = columns.stream()
            .filter(column -> !name.equalsIgnoreCase(text(column.get("name"))))
            .toList();
        return new ChangePlan("DROP_COLUMN", "ALTER TABLE " + target + " DROP COLUMN " + quote(name), next);
    }

    private ChangePlan renameColumnPlan(String target, List<Map<String, Object>> columns, Map<String, Object> payload) {
        String name = identifier(required(payload, "columnName"), "字段名不合法");
        String afterName = identifier(required(payload, "afterName"), "新字段名不合法");
        if (findColumn(columns, name).isEmpty()) {
            throw new BizException(40463, "字段不存在: " + name);
        }
        if (findColumn(columns, afterName).isPresent()) {
            throw new BizException(40963, "目标字段已存在: " + afterName);
        }
        List<Map<String, Object>> next = copyColumns(columns);
        findColumn(next, name).ifPresent(column -> column.put("name", afterName));
        return new ChangePlan("RENAME_COLUMN", "ALTER TABLE " + target + " RENAME COLUMN " + quote(name) + " TO " + quote(afterName), next);
    }

    private ChangePlan changeTypePlan(String target, List<Map<String, Object>> columns, Map<String, Object> payload) {
        String name = identifier(required(payload, "columnName"), "字段名不合法");
        String type = trinoType(required(payload, "afterType"));
        if (findColumn(columns, name).isEmpty()) {
            throw new BizException(40463, "字段不存在: " + name);
        }
        List<Map<String, Object>> next = copyColumns(columns);
        findColumn(next, name).ifPresent(column -> column.put("type", type));
        return new ChangePlan("CHANGE_TYPE", "ALTER TABLE " + target + " ALTER COLUMN " + quote(name) + " SET DATA TYPE " + type, next);
    }

    private ApprovalSnapshot loadApproval(UUID tenantId, UUID approvalId) {
        try {
            Map<String, Object> row = jdbc.queryForMap("""
                SELECT request_type, target_ref, status, payload
                FROM security.approval_request
                WHERE tenant_id = ? AND id = ?
                """, tenantId, approvalId);
            return new ApprovalSnapshot(
                text(row.get("request_type")),
                text(row.get("target_ref")),
                text(row.get("status")),
                payloadMap(row.get("payload"))
            );
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new BizException(40463, "Schema 变更审批单不存在");
        }
    }

    private void markExecution(UUID approvalId, Map<String, Object> payload, String status, String sql, String error) {
        markExecution(approvalId, payload, status, sql, error, Instant.now());
    }

    private void markExecution(UUID approvalId, Map<String, Object> payload, String status, String sql, String error, Instant at) {
        Map<String, Object> next = new LinkedHashMap<>(payload);
        next.put("executionStatus", status);
        next.put("executionSql", sql);
        next.put("executedAt", at.toString());
        next.put("executedBy", TenantContext.getUserId() == null ? null : TenantContext.getUserId().toString());
        if (error == null || error.isBlank()) {
            next.remove("executionError");
        } else {
            next.put("executionError", error);
        }
        jdbc.update(
            "UPDATE security.approval_request SET payload = CAST(? AS jsonb) WHERE id = ? AND tenant_id = ?",
            JsonUtil.toJson(next),
            approvalId,
            requireTenant()
        );
    }

    private List<Map<String, Object>> parseColumns(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ArrayList<>();
        }
        JsonNode node = JsonUtil.parse(raw);
        if (!node.isArray()) {
            throw new BizException(40063, "资产字段元数据格式异常");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (JsonNode item : node) {
            result.add(JsonUtil.mapper().convertValue(item, new TypeReference<LinkedHashMap<String, Object>>() {}));
        }
        return result;
    }

    private Map<String, Object> payloadMap(Object payload) {
        if (payload == null) {
            return new LinkedHashMap<>();
        }
        if (payload instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, value) -> result.put(String.valueOf(key), value));
            return result;
        }
        String text = String.valueOf(payload);
        if (!text.isBlank()) {
            try {
                return JsonUtil.mapper().readValue(text, new TypeReference<LinkedHashMap<String, Object>>() {});
            } catch (Exception ignored) {
                return new LinkedHashMap<>();
            }
        }
        return new LinkedHashMap<>();
    }

    private Optional<Map<String, Object>> findColumn(List<Map<String, Object>> columns, String name) {
        return columns.stream()
            .filter(column -> name.equalsIgnoreCase(text(column.get("name"))))
            .findFirst();
    }

    private List<Map<String, Object>> copyColumns(List<Map<String, Object>> columns) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> column : columns) {
            result.add(new LinkedHashMap<>(column));
        }
        return result;
    }

    private String maxClassification(List<Map<String, Object>> columns) {
        String max = null;
        for (Map<String, Object> column : columns) {
            String classification = normalizeClassification(text(column.get("classification")));
            if (classification != null && (max == null || classification.compareTo(max) > 0)) {
                max = classification;
            }
        }
        return max;
    }

    private String trinoType(String raw) {
        String type = required(raw, "字段类型不能为空").toUpperCase(Locale.ROOT);
        return switch (type) {
            case "STRING", "VARCHAR" -> "VARCHAR";
            case "BIGINT" -> "BIGINT";
            case "INT", "INTEGER" -> "INTEGER";
            case "DOUBLE" -> "DOUBLE";
            case "TIMESTAMP" -> "TIMESTAMP";
            case "DATE" -> "DATE";
            case "BOOLEAN" -> "BOOLEAN";
            default -> {
                Matcher decimal = DECIMAL.matcher(type);
                if (!decimal.matches()) {
                    throw new BizException(40063, "不支持的字段类型: " + raw);
                }
                int precision = Integer.parseInt(decimal.group(1));
                int scale = Integer.parseInt(decimal.group(2));
                if (precision < 1 || precision > 38 || scale < 0 || scale > precision) {
                    throw new BizException(40063, "DECIMAL 精度不合法: " + raw);
                }
                yield "DECIMAL(" + precision + "," + scale + ")";
            }
        };
    }

    private String normalizeClassification(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (value.isBlank()) {
            return null;
        }
        if (!CLASSIFICATIONS.contains(value)) {
            throw new BizException(40063, "密级必须是 L1/L2/L3/L4: " + raw);
        }
        return value;
    }

    private TableRef tableRef(String fqn) {
        if (fqn == null || fqn.isBlank()) {
            throw new BizException(40063, "资产 FQN 不能为空");
        }
        String[] parts = fqn.split("\\.");
        if (parts.length != 2) {
            throw new BizException(40063, "仅支持 schema.table 形式的 Iceberg 表: " + fqn);
        }
        return new TableRef(identifier(parts[0], "schema 不合法"), identifier(parts[1], "table 不合法"));
    }

    private String identifier(String raw, String message) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!IDENTIFIER.matcher(value).matches()) {
            throw new BizException(40063, message + ": " + raw);
        }
        return value;
    }

    private String required(Map<String, Object> payload, String key) {
        return required(payload.get(key), key + " 不能为空");
    }

    private String required(Object value, String message) {
        String text = text(value);
        if (text.isBlank()) {
            throw new BizException(40063, message);
        }
        return text.trim();
    }

    private UUID requireTenant() {
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new BizException(40100, "租户上下文缺失");
        }
        return tenantId;
    }

    private String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private Instant parseInstant(Object value) {
        String text = text(value);
        return text.isBlank() ? null : Instant.parse(text);
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private record ApprovalSnapshot(
        String requestType,
        String targetRef,
        String status,
        Map<String, Object> payload
    ) {}

    private record ChangePlan(
        String changeType,
        String sql,
        List<Map<String, Object>> columns
    ) {}

    private record TableRef(String schema, String table) {}
}
