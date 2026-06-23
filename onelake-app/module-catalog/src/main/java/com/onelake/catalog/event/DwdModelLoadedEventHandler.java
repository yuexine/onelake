package com.onelake.catalog.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class DwdModelLoadedEventHandler implements DomainEventHandler {

    private final AssetRepository assetRepo;
    private final LineageEdgeRepository lineageRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Set<String> eventTypes() {
        return Set.of(DomainEvents.MODELING_MODEL_LOADED);
    }

    @Override
    @Transactional
    public void handle(OutboxEvent event) {
        try {
            JsonNode payload = objectMapper.readTree(event.getPayload() == null ? "{}" : event.getPayload());
            String tenantIdRaw = payload.path("tenantId").asText("");
            String sourceFqn = payload.path("sourceFqn").asText("");
            String targetFqn = payload.path("targetFqn").asText("");
            if (tenantIdRaw.isBlank() || sourceFqn.isBlank() || targetFqn.isBlank()) {
                log.warn("DwdModelLoadedEventHandler skipped event {} (missing tenantId/sourceFqn/targetFqn)", event.getId());
                return;
            }

            UUID tenantId;
            try {
                tenantId = UUID.fromString(tenantIdRaw);
            } catch (IllegalArgumentException e) {
                log.warn("DwdModelLoadedEventHandler skipped event {} (bad tenantId {})", event.getId(), tenantIdRaw);
                return;
            }

            Asset asset = assetRepo.findByTenantIdAndOmFqn(tenantId, targetFqn)
                .orElseGet(() -> newDwdAsset(tenantId, targetFqn));
            asset.setLayer("DWD");
            asset.setAssetType("TABLE");
            asset.setDisplayName(tableNameOf(targetFqn));
            asset.setDescription("由 DWD 模型运行自动登记");
            asset.setTags("[\"modeling\",\"dwd\",\"auto\"]");
            asset.setColumns(JsonUtil.toJson(columnsOf(payload.path("fieldMapping"))));
            asset.setRowCount(payload.path("rowsWritten").asLong(asset.getRowCount() == null ? 0L : asset.getRowCount()));
            UUID ownerId = uuidOrNull(payload.path("ownerId").asText(""));
            if (ownerId != null) {
                asset.setOwnerId(ownerId);
            }
            String ownerName = payload.path("ownerName").asText("");
            if (!ownerName.isBlank()) {
                asset.setOwnerName(ownerName);
            }
            asset.setFormat("ICEBERG");
            asset.setLastSyncAt(Instant.now());
            asset.setSyncedAt(Instant.now());
            assetRepo.save(asset);

            LineageEdge edge = lineageRepo.findByTenantIdAndUpstreamFqnAndDownstreamFqn(tenantId, sourceFqn, targetFqn)
                .orElseGet(LineageEdge::new);
            edge.setTenantId(tenantId);
            edge.setUpstreamFqn(sourceFqn);
            edge.setDownstreamFqn(targetFqn);
            edge.setColumnLevel(JsonUtil.toJson(columnLineageOf(payload.path("fieldMapping"))));
            edge.setJobRef(payload.path("runId").asText(event.getAggregateId()));
            edge.setSyncedAt(Instant.now());
            lineageRepo.save(edge);
            log.info("DwdModelLoadedEventHandler: DWD asset {} and lineage {} -> {} upserted",
                targetFqn, sourceFqn, targetFqn);
        } catch (Exception e) {
            log.error("DwdModelLoadedEventHandler failed for event {}: {}", event.getId(), e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    private Asset newDwdAsset(UUID tenantId, String targetFqn) {
        Asset asset = new Asset();
        asset.setTenantId(tenantId);
        asset.setOmFqn(targetFqn);
        asset.setPartitions("[]");
        asset.setPopularity(0);
        asset.setAccessCount(0);
        return asset;
    }

    private List<Map<String, Object>> columnsOf(JsonNode fieldMapping) {
        List<Map<String, Object>> columns = new ArrayList<>();
        if (fieldMapping != null && fieldMapping.isArray()) {
            for (JsonNode item : fieldMapping) {
                String name = item.path("target").asText("");
                if (name.isBlank()) continue;
                Map<String, Object> column = new LinkedHashMap<>();
                column.put("name", name);
                column.put("type", item.path("targetType").asText("STRING"));
                String source = item.path("source").asText("");
                if (!source.isBlank()) column.put("description", "DWD 模型字段，源字段: " + source);
                copyText(column, item, "classification");
                copyText(column, item, "suggestLevel");
                copyText(column, item, "piiType");
                columns.add(column);
            }
        }
        return columns;
    }

    private List<Map<String, String>> columnLineageOf(JsonNode fieldMapping) {
        List<Map<String, String>> lineage = new ArrayList<>();
        if (fieldMapping != null && fieldMapping.isArray()) {
            for (JsonNode item : fieldMapping) {
                String source = item.path("source").asText("");
                String target = item.path("target").asText("");
                if (source.isBlank() || target.isBlank()) continue;
                // modeling 的 fieldMapping 用 expression 字段（DwdModelDraftRequest.ColumnMappingRequest.expression）
                String transform = item.path("transform").asText("");
                if (transform.isBlank()) transform = item.path("expression").asText("");
                Map<String, String> entry = new java.util.HashMap<>();
                entry.put("from", source);
                entry.put("to", target);
                if (!transform.isBlank()) entry.put("transform", transform);
                lineage.add(entry);
            }
        }
        return lineage;
    }

    private void copyText(Map<String, Object> target, JsonNode source, String field) {
        String value = source.path(field).asText("");
        if (!value.isBlank()) {
            target.put(field, value);
        }
    }

    private String tableNameOf(String fqn) {
        if (fqn == null || fqn.isBlank()) return "-";
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 && dot < fqn.length() - 1 ? fqn.substring(dot + 1) : fqn;
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
}
