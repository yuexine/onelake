package com.onelake.catalog.service;

import com.onelake.catalog.domain.entity.Asset;
import com.onelake.catalog.domain.entity.LineageEdge;
import com.onelake.catalog.dto.AssetDTO;
import com.onelake.catalog.repository.AssetRepository;
import com.onelake.catalog.repository.LineageEdgeRepository;
import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CatalogService {

    private final AssetRepository assetRepo;
    private final LineageEdgeRepository lineageRepo;

    @Transactional(readOnly = true)
    public AssetDTO getAsset(UUID id) {
        return toDTO(assetRepo.findById(id)
            .orElseThrow(() -> new BizException(40400, "资产不存在")));
    }

    @Transactional(readOnly = true)
    public List<AssetDTO> listByLayer(String layer) {
        UUID tenantId = TenantContext.getTenantId();
        List<Asset> assets = layer == null || layer.isBlank()
            ? assetRepo.findByTenantId(tenantId)
            : assetRepo.findByTenantIdAndLayer(tenantId, layer.toUpperCase());
        return assets.stream().map(this::toDTO).toList();
    }

    /** 影响分析：以下游 fqn 为根，BFS 找出所有下游资产。 */
    @Transactional(readOnly = true)
    public List<String> downstream(UUID tenantId, String rootFqn) {
        if (rootFqn == null || rootFqn.isBlank()) return Collections.emptyList();
        Set<String> visited = new HashSet<>();
        List<String> result = new ArrayList<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        visited.add(rootFqn);
        queue.add(rootFqn);

        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            for (LineageEdge edge : lineageRepo.findByTenantIdAndUpstreamFqn(tenantId, current)) {
                String downstream = edge.getDownstreamFqn();
                if (downstream == null || downstream.isBlank() || !visited.add(downstream)) {
                    continue;
                }
                result.add(downstream);
                queue.add(downstream);
            }
        }
        return result;
    }

    private AssetDTO toDTO(Asset asset) {
        String fqn = asset.getOmFqn();
        return new AssetDTO(
            asset.getId(),
            fqn,
            shortName(fqn, asset.getDisplayName()),
            asset.getAssetType(),
            layerOf(asset),
            domainOf(asset),
            asset.getOwnerId(),
            textOrDefault(asset.getOwnerName(), "-"),
            asset.getDescription(),
            parseTags(asset.getTags()),
            asset.getClassification(),
            asset.getQualityScore(),
            asset.getPopularity() == null ? 0 : asset.getPopularity(),
            asset.getAccessCount() == null ? 0 : asset.getAccessCount(),
            asset.getRowCount(),
            asset.getSizeBytes(),
            parseColumns(asset.getColumns()),
            parseTags(asset.getPartitions()),
            asset.getFormat(),
            asset.getLastSyncAt(),
            asset.getSyncedAt()
        );
    }

    private String layerOf(Asset asset) {
        if (asset.getLayer() != null && !asset.getLayer().isBlank()) return asset.getLayer();
        String fqn = asset.getOmFqn();
        if (fqn == null || fqn.isBlank()) return null;
        int dot = fqn.indexOf('.');
        String prefix = dot > 0 ? fqn.substring(0, dot).toUpperCase() : "";
        if (prefix.startsWith("ODS")) return "ODS";
        if (prefix.startsWith("DWD")) return "DWD";
        if (prefix.startsWith("DWS")) return "DWS";
        if (prefix.startsWith("ADS")) return "ADS";
        return null;
    }

    private String domainOf(Asset asset) {
        if (asset.getDomain() != null && !asset.getDomain().isBlank()) return asset.getDomain();
        Set<String> technicalTags = Set.of("integration", "auto", "security", "pii-detected");
        for (String tag : parseTags(asset.getTags())) {
            String normalized = tag.trim();
            if (!normalized.isBlank()
                && !technicalTags.contains(normalized.toLowerCase())
                && !normalized.contains(".")
                && !normalized.matches("(?i).*L[1-4]$")) {
                return normalized.endsWith("域") ? normalized.substring(0, normalized.length() - 1) : normalized;
            }
        }
        return "未归属";
    }

    private String textOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String shortName(String fqn, String displayName) {
        if (displayName != null && !displayName.isBlank()) return displayName;
        if (fqn == null || fqn.isBlank()) return "-";
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 && dot < fqn.length() - 1 ? fqn.substring(dot + 1) : fqn;
    }

    private List<String> parseTags(String raw) {
        if (raw == null || raw.isBlank()) return Collections.emptyList();
        try {
            com.fasterxml.jackson.databind.JsonNode node = JsonUtil.parse(raw);
            if (!node.isArray()) return Collections.emptyList();
            java.util.ArrayList<String> tags = new java.util.ArrayList<>();
            node.forEach(item -> {
                if (item.isTextual()) tags.add(item.asText());
            });
            return tags;
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    private List<AssetDTO.AssetColumnDTO> parseColumns(String raw) {
        if (raw == null || raw.isBlank()) return Collections.emptyList();
        try {
            com.fasterxml.jackson.databind.JsonNode node = JsonUtil.parse(raw);
            if (!node.isArray()) return Collections.emptyList();
            java.util.ArrayList<AssetDTO.AssetColumnDTO> columns = new java.util.ArrayList<>();
            node.forEach(item -> {
                String name = item.path("name").asText("");
                if (name.isBlank()) return;
                columns.add(new AssetDTO.AssetColumnDTO(
                    name,
                    item.path("type").asText("-"),
                    item.path("description").asText(null),
                    item.path("classification").asText(null),
                    item.path("piiType").asText(null),
                    item.path("suggestLevel").asText(null)
                ));
            });
            return columns;
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }
}
