package com.onelake.catalog.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onelake.catalog.config.TrinoConnectionFactory;
import com.onelake.catalog.domain.entity.Asset;
import com.onelake.catalog.domain.entity.LineageEdge;
import com.onelake.catalog.repository.AssetRepository;
import com.onelake.catalog.repository.LineageEdgeRepository;
import com.onelake.common.outbox.DomainEventHandler;
import com.onelake.common.outbox.DomainEvents;
import com.onelake.common.outbox.OutboxEvent;
import com.onelake.common.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineTaskLoadedEventHandler implements DomainEventHandler {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final AssetRepository assetRepo;
    private final LineageEdgeRepository lineageRepo;
    private final TrinoConnectionFactory trinoConnectionFactory;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Set<String> eventTypes() {
        return Set.of(DomainEvents.PIPELINE_TASK_LOADED);
    }

    @Override
    @Transactional
    public void handle(OutboxEvent event) {
        try {
            JsonNode payload = objectMapper.readTree(event.getPayload() == null ? "{}" : event.getPayload());
            if (!isSparkTask(payload)) {
                return;
            }
            UUID tenantId = uuidOrNull(payload.path("tenantId").asText(""));
            String targetFqn = normalizeFqn(payload.path("targetFqn").asText(""));
            if (tenantId == null || targetFqn.isBlank()) {
                log.warn("PipelineTaskLoadedEventHandler skipped event {} (missing tenantId/targetFqn)", event.getId());
                return;
            }

            JsonNode catalog = payload.path("catalog");
            List<QualitySpec> qualitySpecs = qualitySpecs(targetFqn, catalog);
            Asset asset = upsertAsset(tenantId, targetFqn, payload, catalog, null,
                !qualitySpecs.isEmpty(), false);
            assetRepo.save(asset);

            for (QualitySpec spec : qualitySpecs) {
                Asset qualityAsset = upsertAsset(tenantId, spec.fqn(), payload, spec.catalog(),
                    spec.columns(), true, true);
                assetRepo.save(qualityAsset);
            }

            upsertLineage(tenantId, payload.path("fromTables"), targetFqn, payload.path("runId").asText(""));
            log.info("PipelineTaskLoadedEventHandler: Spark asset {} upserted from pipeline task {}", targetFqn,
                payload.path("taskKey").asText(""));
        } catch (Exception e) {
            log.error("PipelineTaskLoadedEventHandler failed for event {}: {}", event.getId(), e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    private boolean isSparkTask(JsonNode payload) {
        String taskType = payload.path("taskType").asText("").toUpperCase(Locale.ROOT);
        String engine = payload.path("engine").asText("").toUpperCase(Locale.ROOT);
        return "SPARK_SQL".equals(taskType)
            || "PYSPARK".equals(taskType)
            || "SPARK_SQL".equals(engine)
            || "PYSPARK".equals(engine)
            || engine.contains("SPARK");
    }

    private Asset upsertAsset(UUID tenantId, String fqn, JsonNode payload, JsonNode catalog,
                              List<Map<String, Object>> forcedColumns,
                              boolean qualityAvailable, boolean qualityAsset) {
        Asset asset = assetRepo.findByTenantIdAndOmFqn(tenantId, fqn)
            .orElseGet(() -> newAsset(tenantId, fqn));
        List<Map<String, Object>> columns = forcedColumns != null && !forcedColumns.isEmpty()
            ? forcedColumns
            : columnsOf(catalog.path("columns"));
        if (columns.isEmpty()) {
            columns = loadTrinoColumns(fqn);
        }
        columns = mergeExistingAnnotations(columns, asset.getColumns());

        asset.setAssetType("TABLE");
        asset.setLayer(layerOf(fqn));
        asset.setDomain(textOrDefault(catalog.path("domain"), asset.getDomain()));
        asset.setDisplayName(textOrDefault(catalog.path("displayName"), tableNameOf(fqn)));
        asset.setDescription(textOrDefault(catalog.path("description"),
            qualityAsset ? "由流水线 Spark 任务自动登记的质量检查结果" : "由流水线 Spark 任务自动登记"));
        setOwner(asset, payload);
        asset.setTags(JsonUtil.toJson(tagsOf(catalog, qualityAsset)));
        asset.setColumns(JsonUtil.toJson(columns));
        String classification = text(catalog.path("classification"));
        asset.setClassification(classification.isBlank() ? firstNonBlank(highestClassification(columns), asset.getClassification()) : classification);
        BigDecimal qualityScore = qualityScoreOf(catalog);
        if (qualityScore != null) {
            asset.setQualityScore(qualityScore);
        } else if (qualityAvailable) {
            asset.setQualityScore(BigDecimal.valueOf(100));
        }
        Long liveRowCount = queryRowCount(fqn);
        if (liveRowCount != null) {
            asset.setRowCount(liveRowCount);
        } else if (!qualityAsset && payload.path("rowsWritten").canConvertToLong()) {
            asset.setRowCount(payload.path("rowsWritten").asLong());
        }
        if (asset.getPartitions() == null) {
            asset.setPartitions("[]");
        }
        asset.setFormat(textOrDefault(catalog.path("format"), "ICEBERG"));
        asset.setLastSyncAt(Instant.now());
        asset.setSyncedAt(Instant.now());
        return asset;
    }

    private Asset newAsset(UUID tenantId, String fqn) {
        Asset asset = new Asset();
        asset.setTenantId(tenantId);
        asset.setOmFqn(fqn);
        asset.setPopularity(0);
        asset.setAccessCount(0);
        asset.setPartitions("[]");
        return asset;
    }

    private List<QualitySpec> qualitySpecs(String targetFqn, JsonNode catalog) {
        List<QualitySpec> specs = new ArrayList<>();
        JsonNode configured = catalog.path("qualityTables");
        if (configured.isArray()) {
            for (JsonNode node : configured) {
                String fqn = normalizeFqn(node.isTextual() ? node.asText("") : node.path("fqn").asText(""));
                if (fqn.isBlank() || fqn.equals(targetFqn)) {
                    continue;
                }
                List<Map<String, Object>> columns = node.isObject() ? columnsOf(node.path("columns")) : List.of();
                if (columns.isEmpty()) {
                    columns = loadTrinoColumns(fqn);
                }
                specs.add(new QualitySpec(fqn, node.isObject() ? node : objectMapper.createObjectNode(), columns));
            }
        }
        if (!specs.isEmpty() || targetFqn.endsWith("_quality_check")) {
            return specs;
        }
        String convention = targetFqn + "_quality_check";
        List<Map<String, Object>> conventionColumns = loadTrinoColumns(convention);
        if (!conventionColumns.isEmpty()) {
            specs.add(new QualitySpec(convention, objectMapper.createObjectNode(), conventionColumns));
        }
        return specs;
    }

    private List<Map<String, Object>> loadTrinoColumns(String fqn) {
        TableRef ref = TableRef.of(fqn);
        if (ref == null) {
            return List.of();
        }
        List<Map<String, Object>> columns = new ArrayList<>();
        String sql = """
            SELECT column_name, data_type
            FROM information_schema.columns
            WHERE table_schema = ? AND table_name = ?
            ORDER BY ordinal_position
            """;
        try (Connection connection = trinoConnectionFactory.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ref.schema());
            statement.setString(2, ref.table());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> column = new LinkedHashMap<>();
                    String name = rs.getString("column_name");
                    column.put("name", name);
                    column.put("type", rs.getString("data_type").toUpperCase(Locale.ROOT));
                    applyColumnHints(column, name);
                    columns.add(column);
                }
            }
        } catch (Exception e) {
            log.debug("Pipeline Spark asset column refresh skipped for {}: {}", fqn, rootMessage(e));
        }
        return columns;
    }

    private Long queryRowCount(String fqn) {
        TableRef ref = TableRef.of(fqn);
        if (ref == null) {
            return null;
        }
        try (Connection connection = trinoConnectionFactory.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT count(*) FROM " + ref.sqlName())) {
            return rs.next() ? rs.getLong(1) : null;
        } catch (Exception e) {
            log.debug("Pipeline Spark asset row count skipped for {}: {}", fqn, rootMessage(e));
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> columnsOf(JsonNode node) {
        List<Map<String, Object>> columns = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return columns;
        }
        for (JsonNode item : node) {
            Map<String, Object> column = objectMapper.convertValue(item, Map.class);
            String name = String.valueOf(column.getOrDefault("name", "")).trim();
            if (name.isBlank()) {
                continue;
            }
            column.put("name", name);
            column.putIfAbsent("type", "VARCHAR");
            applyColumnHints(column, name);
            columns.add(column);
        }
        return columns;
    }

    private List<Map<String, Object>> mergeExistingAnnotations(List<Map<String, Object>> columns, String existingColumnsJson) {
        if (columns.isEmpty() || existingColumnsJson == null || existingColumnsJson.isBlank()) {
            return columns;
        }
        Map<String, JsonNode> existingByName = new LinkedHashMap<>();
        try {
            JsonNode existing = JsonUtil.parse(existingColumnsJson);
            if (existing.isArray()) {
                for (JsonNode column : existing) {
                    String name = column.path("name").asText("");
                    if (!name.isBlank()) {
                        existingByName.put(name, column);
                    }
                }
            }
        } catch (RuntimeException ignored) {
            return columns;
        }
        for (Map<String, Object> column : columns) {
            JsonNode existing = existingByName.get(String.valueOf(column.get("name")));
            copyIfMissing(column, existing, "description");
            copyIfMissing(column, existing, "classification");
            copyIfMissing(column, existing, "suggestLevel");
            copyIfMissing(column, existing, "piiType");
            copyIfMissing(column, existing, "piiConfidence");
        }
        return columns;
    }

    private void copyIfMissing(Map<String, Object> target, JsonNode source, String field) {
        if (source == null || target.containsKey(field)) {
            return;
        }
        JsonNode value = source.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return;
        }
        if (value.isNumber()) {
            target.put(field, value.asDouble());
        } else if (value.isBoolean()) {
            target.put(field, value.asBoolean());
        } else {
            String text = value.asText("");
            if (!text.isBlank()) {
                target.put(field, text);
            }
        }
    }

    private void applyColumnHints(Map<String, Object> column, String rawName) {
        String name = rawName == null ? "" : rawName.toLowerCase(Locale.ROOT);
        if (name.contains("id_card") || name.contains("idcard") || name.contains("identity")) {
            column.putIfAbsent("description", "已脱敏的身份证号");
            column.putIfAbsent("piiType", "身份证");
            column.putIfAbsent("classification", "L4");
            column.putIfAbsent("suggestLevel", "L4");
        } else if (name.contains("phone") || name.contains("mobile")) {
            column.putIfAbsent("description", "已脱敏的手机号");
            column.putIfAbsent("piiType", "手机号");
            column.putIfAbsent("classification", "L3");
            column.putIfAbsent("suggestLevel", "L3");
        } else if (name.equals("user_name") || name.equals("username") || name.equals("name")) {
            column.putIfAbsent("description", "用户名称");
            column.putIfAbsent("piiType", "姓名");
            column.putIfAbsent("classification", "L3");
            column.putIfAbsent("suggestLevel", "L3");
        } else if (name.contains("desc") || name.contains("description")) {
            column.putIfAbsent("description", "已执行去空格治理的描述字段");
            column.putIfAbsent("classification", "L2");
        } else if (name.contains("uuid")) {
            column.putIfAbsent("description", "用户 UUID");
            column.putIfAbsent("classification", "L2");
        }
    }

    private void setOwner(Asset asset, JsonNode payload) {
        UUID ownerId = uuidOrNull(payload.path("ownerId").asText(""));
        if (ownerId != null) {
            asset.setOwnerId(ownerId);
        }
        String ownerName = payload.path("ownerName").asText("");
        if (!ownerName.isBlank()) {
            asset.setOwnerName(ownerName);
        }
    }

    private void upsertLineage(UUID tenantId, JsonNode fromTables, String targetFqn, String runId) {
        if (fromTables == null || !fromTables.isArray()) {
            return;
        }
        for (JsonNode table : fromTables) {
            String upstreamFqn = normalizeFqn(table.asText(""));
            if (upstreamFqn.isBlank()) {
                continue;
            }
            LineageEdge edge = lineageRepo.findByTenantIdAndUpstreamFqnAndDownstreamFqn(tenantId, upstreamFqn, targetFqn)
                .orElseGet(LineageEdge::new);
            edge.setTenantId(tenantId);
            edge.setUpstreamFqn(upstreamFqn);
            edge.setDownstreamFqn(targetFqn);
            edge.setColumnLevel("[]");
            edge.setJobRef(runId == null || runId.isBlank() ? null : runId);
            edge.setSyncedAt(Instant.now());
            lineageRepo.save(edge);
        }
    }

    private List<String> tagsOf(JsonNode catalog, boolean qualityAsset) {
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        tags.add("pipeline");
        tags.add("spark");
        tags.add("auto");
        if (qualityAsset) {
            tags.add("quality");
        }
        JsonNode configured = catalog.path("tags");
        if (configured.isArray()) {
            for (JsonNode tag : configured) {
                String value = tag.asText("");
                if (!value.isBlank()) {
                    tags.add(value);
                }
            }
        }
        return new ArrayList<>(tags);
    }

    private BigDecimal qualityScoreOf(JsonNode catalog) {
        JsonNode value = catalog.path("qualityScore");
        if (value.isNumber()) {
            return BigDecimal.valueOf(value.asDouble());
        }
        String text = value.asText("");
        if (text.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String highestClassification(List<Map<String, Object>> columns) {
        String best = null;
        for (Map<String, Object> column : columns) {
            String value = String.valueOf(column.getOrDefault("classification", "")).toUpperCase(Locale.ROOT);
            if (!value.matches("L[1-4]")) {
                continue;
            }
            if (best == null || rank(value) > rank(best)) {
                best = value;
            }
        }
        return best;
    }

    private int rank(String classification) {
        return classification == null || classification.length() < 2 ? 0 : classification.charAt(1) - '0';
    }

    private String normalizeFqn(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String value = raw.trim().replace("\"", "");
        String[] parts = value.split("\\.");
        if (parts.length >= 3) {
            String first = parts[0].toLowerCase(Locale.ROOT);
            if ("iceberg".equals(first) || "onelake".equals(first) || "hive".equals(first)) {
                return parts[parts.length - 2] + "." + parts[parts.length - 1];
            }
        }
        if (parts.length >= 2) {
            return parts[parts.length - 2] + "." + parts[parts.length - 1];
        }
        return value;
    }

    private String layerOf(String fqn) {
        String schema = schemaOf(fqn).toUpperCase(Locale.ROOT);
        if (schema.startsWith("ODS")) return "ODS";
        if (schema.startsWith("DWD")) return "DWD";
        if (schema.startsWith("DWS")) return "DWS";
        if (schema.startsWith("ADS")) return "ADS";
        return null;
    }

    private String schemaOf(String fqn) {
        String[] parts = fqn == null ? new String[0] : fqn.split("\\.");
        return parts.length >= 2 ? parts[parts.length - 2] : "";
    }

    private String tableNameOf(String fqn) {
        if (fqn == null || fqn.isBlank()) {
            return "-";
        }
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 && dot < fqn.length() - 1 ? fqn.substring(dot + 1) : fqn;
    }

    private String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? "" : node.asText("").trim();
    }

    private String textOrDefault(JsonNode node, String fallback) {
        String value = text(node);
        return value.isBlank() ? fallback : value;
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private UUID uuidOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && !message.isBlank()) {
                return message;
            }
            current = current.getCause();
        }
        return throwable.getClass().getSimpleName();
    }

    private record QualitySpec(String fqn, JsonNode catalog, List<Map<String, Object>> columns) {}

    private record TableRef(String schema, String table) {
        static TableRef of(String fqn) {
            if (fqn == null || fqn.isBlank()) {
                return null;
            }
            String[] parts = fqn.trim().split("\\.");
            if (parts.length < 2) {
                return null;
            }
            String schema = parts[parts.length - 2];
            String table = parts[parts.length - 1];
            if (!IDENTIFIER.matcher(schema).matches() || !IDENTIFIER.matcher(table).matches()) {
                return null;
            }
            return new TableRef(schema, table);
        }

        String sqlName() {
            return quote(schema) + "." + quote(table);
        }

        private static String quote(String value) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
    }
}
