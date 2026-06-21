package com.onelake.catalog.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onelake.catalog.domain.entity.Asset;
import com.onelake.catalog.repository.AssetRepository;
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
 * 将安全模块 PII 扫描结果反哺到 Catalog 字段 schema。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PiiDetectedEventHandler implements DomainEventHandler {

    private final AssetRepository assetRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Set<String> eventTypes() {
        return Set.of(DomainEvents.SECURITY_PII_DETECTED);
    }

    @Override
    @Transactional
    public void handle(OutboxEvent event) {
        try {
            JsonNode payload = objectMapper.readTree(event.getPayload() == null ? "{}" : event.getPayload());
            String tableFqn = payload.path("tableFqn").asText("");
            String tenantIdRaw = payload.path("tenantId").asText("");
            if (tableFqn.isBlank() || tenantIdRaw.isBlank()) {
                log.warn("PiiDetectedEventHandler skipped event {} (missing tableFqn/tenantId)", event.getId());
                return;
            }

            UUID tenantId;
            try {
                tenantId = UUID.fromString(tenantIdRaw);
            } catch (IllegalArgumentException e) {
                log.warn("PiiDetectedEventHandler skipped event {} (bad tenantId {})", event.getId(), tenantIdRaw);
                return;
            }

            JsonNode detections = payload.path("detections");
            if (!detections.isArray() || detections.isEmpty()) {
                log.info("PiiDetectedEventHandler skipped event {} (no detections)", event.getId());
                return;
            }

            Asset asset = assetRepo.findByTenantIdAndOmFqn(tenantId, tableFqn)
                .orElseGet(() -> newAsset(tenantId, tableFqn));
            asset.setColumns(JsonUtil.toJson(mergeDetections(asset.getColumns(), detections)));
            asset.setClassification(maxLevel(asset.getClassification(), detections));
            asset.setSyncedAt(Instant.now());
            assetRepo.save(asset);
            log.info("PiiDetectedEventHandler: asset {} enriched with {} PII detections",
                tableFqn, detections.size());
        } catch (Exception e) {
            log.error("PiiDetectedEventHandler failed for event {}: {}", event.getId(), e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    private Asset newAsset(UUID tenantId, String tableFqn) {
        Asset asset = new Asset();
        asset.setTenantId(tenantId);
        asset.setOmFqn(tableFqn);
        asset.setAssetType("TABLE");
        asset.setLayer(layerOf(tableFqn));
        asset.setDisplayName(tableNameOf(tableFqn));
        asset.setDescription("由安全扫描自动登记，等待采集成功后刷新字段 schema");
        asset.setTags("[\"security\",\"pii-detected\"]");
        asset.setColumns("[]");
        return asset;
    }

    private List<Map<String, Object>> mergeDetections(String rawColumns, JsonNode detections) {
        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        for (Map<String, Object> column : parseColumns(rawColumns)) {
            Object name = column.get("name");
            if (name == null || String.valueOf(name).isBlank()) continue;
            byName.put(String.valueOf(name), new LinkedHashMap<>(column));
        }

        for (JsonNode detection : detections) {
            String name = detection.path("column").asText("");
            if (name.isBlank()) name = suffixOf(detection.path("fqn").asText(""));
            if (name.isBlank()) continue;
            String columnName = name;

            Map<String, Object> column = byName.computeIfAbsent(columnName, ignored -> {
                Map<String, Object> created = new LinkedHashMap<>();
                created.put("name", columnName);
                created.put("type", "-");
                return created;
            });
            String level = detection.path("suggestLevel").asText("");
            if (!level.isBlank()) {
                column.put("classification", level);
                column.put("suggestLevel", level);
            }
            String piiType = detection.path("piiType").asText("");
            if (!piiType.isBlank()) {
                column.put("piiType", piiType);
            }
            if (detection.has("confidence")) {
                column.put("piiConfidence", detection.path("confidence").asDouble());
            }
        }
        return new ArrayList<>(byName.values());
    }

    private List<Map<String, Object>> parseColumns(String rawColumns) {
        if (rawColumns == null || rawColumns.isBlank()) return new ArrayList<>();
        try {
            JsonNode node = JsonUtil.parse(rawColumns);
            if (!node.isArray()) return new ArrayList<>();
            List<Map<String, Object>> columns = new ArrayList<>();
            for (JsonNode item : node) {
                columns.add(objectMapper.convertValue(item, Map.class));
            }
            return columns;
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
    }

    private String maxLevel(String existing, JsonNode detections) {
        String max = normalizeLevel(existing);
        for (JsonNode detection : detections) {
            String level = normalizeLevel(detection.path("suggestLevel").asText(""));
            if (levelRank(level) > levelRank(max)) {
                max = level;
            }
        }
        return max.isBlank() ? null : max;
    }

    private String normalizeLevel(String level) {
        if (level == null) return "";
        String normalized = level.trim().toUpperCase();
        return normalized.matches("L[1-4]") ? normalized : "";
    }

    private int levelRank(String level) {
        return switch (normalizeLevel(level)) {
            case "L1" -> 1;
            case "L2" -> 2;
            case "L3" -> 3;
            case "L4" -> 4;
            default -> 0;
        };
    }

    private String suffixOf(String fqn) {
        if (fqn == null || fqn.isBlank()) return "";
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 && dot < fqn.length() - 1 ? fqn.substring(dot + 1) : fqn;
    }

    private String layerOf(String fqn) {
        if (fqn == null || fqn.isBlank()) return null;
        int dot = fqn.indexOf('.');
        String prefix = dot > 0 ? fqn.substring(0, dot).toUpperCase() : "";
        if (prefix.startsWith("ODS")) return "ODS";
        if (prefix.startsWith("DWD")) return "DWD";
        if (prefix.startsWith("DWS")) return "DWS";
        if (prefix.startsWith("ADS")) return "ADS";
        return null;
    }

    private String tableNameOf(String fqn) {
        if (fqn == null || fqn.isBlank()) return "-";
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 && dot < fqn.length() - 1 ? fqn.substring(dot + 1) : fqn;
    }
}
