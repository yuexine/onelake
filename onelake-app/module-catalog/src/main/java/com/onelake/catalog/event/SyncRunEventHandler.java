package com.onelake.catalog.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onelake.catalog.client.OpenMetadataClient;
import com.onelake.catalog.domain.entity.Asset;
import com.onelake.catalog.domain.entity.LineageEdge;
import com.onelake.catalog.repository.AssetRepository;
import com.onelake.catalog.repository.LineageEdgeRepository;
import com.onelake.common.outbox.DomainEvents;
import com.onelake.common.outbox.DomainEventHandler;
import com.onelake.common.outbox.OutboxEvent;
import com.onelake.common.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 消费 integration.table.loaded / integration.sync.failed 事件，维护 catalog 资产的新鲜度。
 *
 * <p>设计意图（CLAUDE.md §3 旅程一）：
 * <ul>
 *   <li>{@code sync_run.succeeded} → 首次建档或刷新资产 syncedAt，标记数据已新鲜</li>
 *   <li>{@code sync_run.failed}    → 仅记录日志，资产不变（由监控/告警单独处理）</li>
 * </ul>
 *
 * <p>payload 字段（来自 integration.SyncTaskServiceImpl.reconcile）：
 * <pre>
 * { "taskId": ..., "externalJobId": ..., "status": ..., "targetTable": "ods.xxx", "tenantId": ... }
 * </pre>
 *
 * <p>定位资产：{@code catalog.asset.om_fqn = payload.targetTable AND tenant_id = payload.tenantId}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SyncRunEventHandler implements DomainEventHandler {

    private final AssetRepository assetRepo;
    private final LineageEdgeRepository lineageRepo;
    private final OpenMetadataClient openMetadataClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Set<String> eventTypes() {
        return Set.of(DomainEvents.INTEGRATION_TABLE_LOADED, DomainEvents.INTEGRATION_SYNC_FAILED);
    }

    @Override
    @Transactional
    public void handle(OutboxEvent event) {
        try {
            JsonNode p = objectMapper.readTree(event.getPayload() == null ? "{}" : event.getPayload());
            String targetTable = p.path("targetTable").asText("");
            String tenantIdRaw = p.path("tenantId").asText("");
            String status = p.path("status").asText("");

            if (targetTable.isBlank() || tenantIdRaw.isBlank()) {
                log.warn("SyncRunEventHandler skipped event {} (missing targetTable/tenantId in payload)", event.getId());
                return;
            }
            UUID tenantId;
            try {
                tenantId = UUID.fromString(tenantIdRaw);
            } catch (IllegalArgumentException e) {
                log.warn("SyncRunEventHandler skipped event {} (bad tenantId {})", event.getId(), tenantIdRaw);
                return;
            }

            if (DomainEvents.INTEGRATION_TABLE_LOADED.equals(event.getEventType())
                || "SUCCEEDED".equalsIgnoreCase(status)) {
                String sourceTable = p.path("sourceTable").asText("");
                String runId = p.path("runId").asText("");
                JsonNode columns = columnsOf(p.path("fieldMapping"));
                Asset a = assetRepo.findByTenantIdAndOmFqn(tenantId, targetTable)
                    .orElseGet(() -> newAsset(tenantId, targetTable, p));
                if (columns.isArray()) {
                    a.setColumns(JsonUtil.toJson(mergeExistingSecurityAnnotations(columns, a.getColumns())));
                }
                long rowsSynced = p.path("rowsSynced").asLong(p.path("rowsWritten").asLong(a.getRowCount() == null ? 0L : a.getRowCount()));
                a.setRowCount(rowsSynced);
                a.setLastSyncAt(Instant.now());
                a.setSyncedAt(Instant.now());
                assetRepo.save(a);
                upsertLineage(tenantId, sourceTable, targetTable, runId, p.path("fieldMapping"));
                writeBackOpenMetadata(sourceTable, targetTable, runId, a, columns);
                log.info("SyncRunEventHandler: asset {} upserted after table.loaded", targetTable);
            } else {
                log.info("SyncRunEventHandler: sync.failed for {} — asset untouched, monitor will alert", targetTable);
            }
        } catch (Exception e) {
            log.error("SyncRunEventHandler failed for event {}: {}", event.getId(), e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    private Asset newAsset(UUID tenantId, String targetTable, JsonNode payload) {
        Asset asset = new Asset();
        asset.setTenantId(tenantId);
        asset.setOmFqn(targetTable);
        asset.setAssetType("TABLE");
        asset.setLayer(layerOf(targetTable, payload.path("namespace").asText("")));
        asset.setDisplayName(tableNameOf(targetTable, payload.path("table").asText("")));
        asset.setDescription("由数据集成任务自动登记");
        asset.setTags("[\"integration\",\"auto\"]");
        asset.setColumns("[]");
        asset.setPartitions("[]");
        asset.setFormat("ICEBERG");
        return asset;
    }

    private void upsertLineage(UUID tenantId, String sourceTable, String targetTable, String runId, JsonNode fieldMapping) {
        if (sourceTable == null || sourceTable.isBlank() || targetTable == null || targetTable.isBlank()) return;
        LineageEdge edge = lineageRepo.findByTenantIdAndUpstreamFqnAndDownstreamFqn(tenantId, sourceTable, targetTable)
            .orElseGet(LineageEdge::new);
        edge.setTenantId(tenantId);
        edge.setUpstreamFqn(sourceTable);
        edge.setDownstreamFqn(targetTable);
        edge.setColumnLevel(JsonUtil.toJson(columnLineageOf(fieldMapping)));
        edge.setJobRef(runId == null || runId.isBlank() ? null : runId);
        edge.setSyncedAt(Instant.now());
        lineageRepo.save(edge);
    }

    private void writeBackOpenMetadata(String sourceTable, String targetTable, String runId, Asset asset, JsonNode columns) {
        try {
            openMetadataClient.upsertIntegrationTable(targetTable, asset.getDisplayName(), asset.getDescription(), columns);
            openMetadataClient.upsertIntegrationLineage(sourceTable, targetTable, runId);
        } catch (Exception e) {
            log.warn("OpenMetadata writeback skipped for {}: {}", targetTable, e.getMessage());
        }
    }

    private JsonNode columnsOf(JsonNode fieldMapping) {
        List<Map<String, Object>> columns = new ArrayList<>();
        if (fieldMapping != null && fieldMapping.isArray()) {
            for (JsonNode item : fieldMapping) {
                String name = item.path("target").asText(item.path("source").asText(""));
                if (name.isBlank()) continue;
                Map<String, Object> column = new LinkedHashMap<>();
                column.put("name", name);
                column.put("type", item.path("targetType").asText(item.path("sourceType").asText("STRING")));
                String source = item.path("source").asText("");
                if (!source.isBlank()) column.put("description", "源字段: " + source);
                String classification = item.path("classification").asText("");
                if (!classification.isBlank()) column.put("classification", classification);
                columns.add(column);
            }
        }
        return objectMapper.valueToTree(columns);
    }

    private JsonNode mergeExistingSecurityAnnotations(JsonNode newColumns, String existingColumns) {
        Map<String, JsonNode> existingByName = new LinkedHashMap<>();
        try {
            JsonNode existing = JsonUtil.parse(existingColumns == null ? "[]" : existingColumns);
            if (existing.isArray()) {
                for (JsonNode column : existing) {
                    String name = column.path("name").asText("");
                    if (!name.isBlank()) existingByName.put(name, column);
                }
            }
        } catch (Exception ignored) {
            // Existing columns are best-effort cache data; bad JSON should not block table.loaded.
        }

        List<Map<String, Object>> merged = new ArrayList<>();
        if (newColumns != null && newColumns.isArray()) {
            for (JsonNode column : newColumns) {
                Map<String, Object> item = objectMapper.convertValue(column, Map.class);
                JsonNode existing = existingByName.get(column.path("name").asText(""));
                copyIfMissing(item, existing, "classification");
                copyIfMissing(item, existing, "suggestLevel");
                copyIfMissing(item, existing, "piiType");
                copyIfMissing(item, existing, "piiConfidence");
                merged.add(item);
            }
        }
        return objectMapper.valueToTree(merged);
    }

    private void copyIfMissing(Map<String, Object> target, JsonNode source, String field) {
        if (source == null || target.containsKey(field)) return;
        JsonNode value = source.path(field);
        if (value.isMissingNode() || value.isNull()) return;
        if (value.isNumber()) {
            target.put(field, value.asDouble());
        } else if (value.isBoolean()) {
            target.put(field, value.asBoolean());
        } else {
            String text = value.asText("");
            if (!text.isBlank()) target.put(field, text);
        }
    }

    private List<Map<String, String>> columnLineageOf(JsonNode fieldMapping) {
        List<Map<String, String>> lineage = new ArrayList<>();
        if (fieldMapping != null && fieldMapping.isArray()) {
            for (JsonNode item : fieldMapping) {
                String source = item.path("source").asText("");
                String target = item.path("target").asText("");
                if (source.isBlank() || target.isBlank()) continue;
                lineage.add(Map.of("from", source, "to", target));
            }
        }
        return lineage;
    }

    private String layerOf(String targetTable, String namespace) {
        String candidate = namespace == null || namespace.isBlank() ? prefixOf(targetTable) : namespace;
        if (candidate == null) return null;
        String normalized = candidate.toUpperCase();
        if (normalized.startsWith("ODS")) return "ODS";
        if (normalized.startsWith("DWD")) return "DWD";
        if (normalized.startsWith("DWS")) return "DWS";
        if (normalized.startsWith("ADS")) return "ADS";
        return null;
    }

    private String prefixOf(String fqn) {
        if (fqn == null || fqn.isBlank()) return null;
        int dot = fqn.indexOf('.');
        return dot > 0 ? fqn.substring(0, dot) : null;
    }

    private String tableNameOf(String fqn, String fallback) {
        if (fallback != null && !fallback.isBlank()) return fallback;
        if (fqn == null || fqn.isBlank()) return "-";
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 && dot < fqn.length() - 1 ? fqn.substring(dot + 1) : fqn;
    }
}
