package com.onelake.catalog.service;

import com.onelake.catalog.config.TrinoConnectionFactory;
import com.onelake.catalog.domain.entity.Asset;
import com.onelake.catalog.dto.AssetDTO;
import com.onelake.catalog.dto.TableCreateRequest;
import com.onelake.catalog.repository.AssetRepository;
import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.util.JsonUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class CatalogTableService {

    private static final Set<String> LAYERS = Set.of("ODS", "DWD", "DWS", "ADS");
    private static final Set<String> CLASSIFICATIONS = Set.of("L1", "L2", "L3", "L4");
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]{0,127}");
    private static final Pattern TABLE_NAME = Pattern.compile("^(ods|dwd|dws|ads)_([a-z]+)_([a-z_]+)_(df|di|dms|d|w|m|y|app)$");
    private static final Pattern DECIMAL = Pattern.compile("(?i)^DECIMAL\\((\\d{1,2}),(\\d{1,2})\\)$");

    private final AssetRepository assetRepo;
    private final CatalogService catalogService;
    private final TrinoConnectionFactory trinoConnectionFactory;

    public CatalogTableService(
        AssetRepository assetRepo,
        CatalogService catalogService,
        TrinoConnectionFactory trinoConnectionFactory
    ) {
        this.assetRepo = assetRepo;
        this.catalogService = catalogService;
        this.trinoConnectionFactory = trinoConnectionFactory;
    }

    @Transactional
    public AssetDTO createTable(TableCreateRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new BizException(40100, "租户上下文缺失");
        }
        TableSpec spec = validate(request);
        String fqn = spec.schema() + "." + spec.table();
        assetRepo.findByTenantIdAndOmFqn(tenantId, fqn).ifPresent(existing -> {
            throw new BizException(40921, "Catalog 已存在同名资产: " + fqn);
        });

        executeCreateTable(spec);
        Asset asset = upsertAsset(tenantId, fqn, spec, request);
        return catalogService.getAsset(asset.getId());
    }

    private TableSpec validate(TableCreateRequest request) {
        if (request == null) {
            throw new BizException(40060, "建表请求不能为空");
        }
        String layer = text(request.layer()).toUpperCase(Locale.ROOT);
        if (!LAYERS.contains(layer)) {
            throw new BizException(40060, "分层必须是 ODS/DWD/DWS/ADS");
        }
        String table = text(request.name()).toLowerCase(Locale.ROOT);
        Matcher matcher = TABLE_NAME.matcher(table);
        if (!matcher.matches()) {
            throw new BizException(40060, "表名必须符合 layer_domain_business_granularity，例如 dwd_trade_order_df");
        }
        if (!matcher.group(1).equals(layer.toLowerCase(Locale.ROOT))) {
            throw new BizException(40060, "表名前缀与所选分层不一致");
        }
        String schema = layer.toLowerCase(Locale.ROOT);
        String domain = textOrDefault(request.domain(), "未归属");
        if (request.columns() == null || request.columns().isEmpty()) {
            throw new BizException(40060, "至少需要一个字段");
        }

        List<ColumnSpec> columns = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (TableCreateRequest.ColumnCreateRequest column : request.columns()) {
            if (column == null) {
                throw new BizException(40060, "字段定义不能为空");
            }
            String name = text(column.name()).toLowerCase(Locale.ROOT);
            if (!IDENTIFIER.matcher(name).matches()) {
                throw new BizException(40060, "字段名不合法: " + name);
            }
            if (!seen.add(name)) {
                throw new BizException(40060, "字段名重复: " + name);
            }
            String type = trinoType(column.type());
            String classification = normalizeClassification(column.classification());
            columns.add(new ColumnSpec(name, type, Boolean.TRUE.equals(column.primaryKey()), classification, text(column.comment())));
        }

        String partitionExpression = partitionExpression(request.partitionStrategy(), columns);
        String assetFormat = assetFormat(request.format());
        String fileFormat = "ORC".equals(assetFormat) ? "ORC" : "PARQUET";
        return new TableSpec(layer, schema, table, domain, columns, partitionExpression, assetFormat, fileFormat);
    }

    private String assetFormat(String raw) {
        String value = text(raw).toUpperCase(Locale.ROOT);
        if (value.isBlank() || "ICEBERG".equals(value)) {
            return "ICEBERG";
        }
        if ("PARQUET".equals(value) || "ORC".equals(value)) {
            return value;
        }
        throw new BizException(40060, "表格式仅支持 ICEBERG/PARQUET/ORC");
    }

    private String trinoType(String raw) {
        String type = text(raw).toUpperCase(Locale.ROOT);
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
                    throw new BizException(40060, "不支持的字段类型: " + raw);
                }
                int precision = Integer.parseInt(decimal.group(1));
                int scale = Integer.parseInt(decimal.group(2));
                if (precision < 1 || precision > 38 || scale < 0 || scale > precision) {
                    throw new BizException(40060, "DECIMAL 精度不合法: " + raw);
                }
                yield "DECIMAL(" + precision + "," + scale + ")";
            }
        };
    }

    private String normalizeClassification(String raw) {
        String value = text(raw).toUpperCase(Locale.ROOT);
        if (value.isBlank()) {
            return null;
        }
        if (!CLASSIFICATIONS.contains(value)) {
            throw new BizException(40060, "密级必须是 L1/L2/L3/L4: " + raw);
        }
        return value;
    }

    private String partitionExpression(String raw, List<ColumnSpec> columns) {
        String value = text(raw);
        if (value.isBlank() || "none".equalsIgnoreCase(value)) {
            return null;
        }
        Matcher matcher = Pattern.compile("(?i)^(days|months|hours)\\(([a-z][a-z0-9_]*)\\)$").matcher(value);
        if (!matcher.matches()) {
            throw new BizException(40060, "分区策略仅支持 days(col)、months(col)、hours(col) 或 none");
        }
        String column = matcher.group(2).toLowerCase(Locale.ROOT);
        if (columns.stream().noneMatch(c -> c.name().equals(column))) {
            throw new BizException(40060, "分区字段不存在: " + column);
        }
        String function = switch (matcher.group(1).toLowerCase(Locale.ROOT)) {
            case "days" -> "day";
            case "months" -> "month";
            case "hours" -> "hour";
            default -> throw new BizException(40060, "不支持的分区策略: " + raw);
        };
        return function + "(" + column + ")";
    }

    private void executeCreateTable(TableSpec spec) {
        String schemaSql = "CREATE SCHEMA IF NOT EXISTS " + spec.schema();
        String createSql = createTableSql(spec);
        try (Connection connection = trinoConnectionFactory.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(schemaSql);
            statement.execute(createSql);
        } catch (SQLException e) {
            throw new BizException(50061, "Trino 建表失败: " + rootMessage(e), e);
        }
    }

    private String createTableSql(TableSpec spec) {
        StringBuilder sql = new StringBuilder();
        sql.append("CREATE TABLE ")
            .append(spec.schema())
            .append(".")
            .append(spec.table())
            .append(" (");
        for (int i = 0; i < spec.columns().size(); i++) {
            ColumnSpec column = spec.columns().get(i);
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(column.name()).append(" ").append(column.type());
        }
        sql.append(") WITH (format = '").append(spec.fileFormat()).append("'");
        if (spec.partitionExpression() != null) {
            sql.append(", partitioning = ARRAY['").append(spec.partitionExpression()).append("']");
        }
        sql.append(")");
        return sql.toString();
    }

    private Asset upsertAsset(UUID tenantId, String fqn, TableSpec spec, TableCreateRequest request) {
        Asset asset = new Asset();
        asset.setTenantId(tenantId);
        asset.setOmFqn(fqn);
        asset.setAssetType("TABLE");
        asset.setLayer(spec.layer());
        asset.setDomain(spec.domain());
        asset.setDisplayName(spec.table());
        asset.setDescription(textOrDefault(request.description(), "由建表向导创建"));
        asset.setOwnerId(TenantContext.getUserId());
        asset.setOwnerName(textOrDefault(TenantContext.getUsername(), "-"));
        asset.setTags(JsonUtil.toJson(tags(spec, request)));
        asset.setColumns(JsonUtil.toJson(columns(spec)));
        asset.setClassification(maxClassification(spec.columns()));
        asset.setRowCount(0L);
        asset.setPartitions(JsonUtil.toJson(spec.partitionExpression() == null ? List.of() : List.of(spec.partitionExpression())));
        asset.setFormat(spec.assetFormat());
        asset.setLastSyncAt(Instant.now());
        asset.setSyncedAt(Instant.now());
        return assetRepo.save(asset);
    }

    private List<String> tags(TableSpec spec, TableCreateRequest request) {
        List<String> tags = new ArrayList<>();
        tags.add(spec.domain());
        tags.add("manual");
        tags.add("lakehouse");
        if (!text(request.compression()).isBlank()) {
            tags.add("compression:" + text(request.compression()).toUpperCase(Locale.ROOT));
        }
        if (request.ttlDays() != null) {
            tags.add("ttl:" + request.ttlDays());
        }
        if (request.coldStorageAfterDays() != null) {
            tags.add("cold_after:" + request.coldStorageAfterDays());
        }
        return tags;
    }

    private List<Map<String, Object>> columns(TableSpec spec) {
        List<Map<String, Object>> columns = new ArrayList<>();
        for (ColumnSpec column : spec.columns()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", column.name());
            item.put("type", column.type());
            if (!column.comment().isBlank()) {
                item.put("description", column.comment());
            }
            if (column.classification() != null) {
                item.put("classification", column.classification());
            }
            if (column.primaryKey()) {
                item.put("primaryKey", true);
            }
            columns.add(item);
        }
        return columns;
    }

    private String maxClassification(List<ColumnSpec> columns) {
        String max = null;
        for (ColumnSpec column : columns) {
            if (column.classification() == null) {
                continue;
            }
            if (max == null || column.classification().compareTo(max) > 0) {
                max = column.classification();
            }
        }
        return max;
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }

    private String textOrDefault(String value, String fallback) {
        String text = text(value);
        return text.isBlank() ? fallback : text;
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private record TableSpec(
        String layer,
        String schema,
        String table,
        String domain,
        List<ColumnSpec> columns,
        String partitionExpression,
        String assetFormat,
        String fileFormat
    ) {}

    private record ColumnSpec(
        String name,
        String type,
        boolean primaryKey,
        String classification,
        String comment
    ) {}
}
