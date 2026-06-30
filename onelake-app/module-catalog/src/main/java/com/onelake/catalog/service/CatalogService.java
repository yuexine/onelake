package com.onelake.catalog.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.onelake.catalog.domain.entity.Asset;
import com.onelake.catalog.domain.entity.LineageEdge;
import com.onelake.catalog.dto.AssetDTO;
import com.onelake.catalog.dto.AssetMetadataUpdateRequest;
import com.onelake.catalog.repository.AssetRepository;
import com.onelake.catalog.repository.LineageEdgeRepository;
import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class CatalogService {

    private final AssetRepository assetRepo;
    private final LineageEdgeRepository lineageRepo;
    private final CatalogRowCountResolver rowCountResolver;
    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public AssetDTO getAsset(UUID id) {
        Asset asset = assetRepo.findById(id)
            .orElseThrow(() -> new BizException(40400, "资产不存在"));
        Map<UUID, Long> liveRowCounts = rowCountResolver.resolve(List.of(asset));
        return toDTO(asset, liveRowCounts.get(asset.getId()));
    }

    @Transactional(readOnly = true)
    public List<AssetDTO> listByLayer(String layer) {
        UUID tenantId = TenantContext.getTenantId();
        List<Asset> assets = layer == null || layer.isBlank()
            ? assetRepo.findByTenantId(tenantId)
            : assetRepo.findByTenantIdAndLayer(tenantId, layer.toUpperCase());
        Map<UUID, Long> liveRowCounts = rowCountResolver.resolve(assets);
        return assets.stream().map(asset -> toDTO(asset, liveRowCounts.get(asset.getId()))).toList();
    }

    @Transactional(readOnly = true)
    public List<AssetDTO> list(String layer, String keyword, String term) {
        UUID tenantId = TenantContext.getTenantId();
        List<Asset> assets = layer == null || layer.isBlank()
            ? assetRepo.findByTenantId(tenantId)
            : assetRepo.findByTenantIdAndLayer(tenantId, layer.toUpperCase());
        String keywordQuery = normalize(keyword);
        String termQuery = normalize(term);
        Set<String> matchedTermAssets = new LinkedHashSet<>();
        if (!termQuery.isBlank()) {
            matchedTermAssets.addAll(assetFqnsByTerm(tenantId, termQuery));
        }
        if (!keywordQuery.isBlank()) {
            matchedTermAssets.addAll(assetFqnsByTerm(tenantId, keywordQuery));
        }
        List<Asset> filtered = assets.stream()
            .filter(asset -> keywordQuery.isBlank() && termQuery.isBlank()
                || matchedTermAssets.contains(asset.getOmFqn())
                || matchesAsset(asset, keywordQuery)
                || matchesAsset(asset, termQuery))
            .toList();
        Map<UUID, Long> liveRowCounts = rowCountResolver.resolve(filtered);
        return filtered.stream().map(asset -> toDTO(asset, liveRowCounts.get(asset.getId()))).toList();
    }

    @Transactional
    public AssetDTO updateMetadata(UUID id, AssetMetadataUpdateRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new BizException(40100, "租户上下文缺失");
        }
        Asset asset = assetRepo.findById(id)
            .filter(item -> tenantId.equals(item.getTenantId()))
            .orElseThrow(() -> new BizException(40400, "资产不存在"));
        if (request == null) {
            throw new BizException(40070, "资产元数据更新请求不能为空");
        }

        if (request.description() != null) {
            asset.setDescription(cleanNullable(request.description()));
        }
        if (request.domain() != null) {
            asset.setDomain(cleanNullable(request.domain()));
        }
        if (request.ownerName() != null) {
            asset.setOwnerName(cleanNullable(request.ownerName()));
        }
        if (request.tags() != null) {
            asset.setTags(JsonUtil.toJson(normalizeTags(request.tags())));
        }
        if (request.columns() != null) {
            asset.setColumns(mergeColumnMetadata(asset.getColumns(), request.columns()));
            asset.setClassification(maxColumnClassification(asset.getColumns()));
        }
        asset.setSyncedAt(Instant.now());

        Asset saved = assetRepo.save(asset);
        Map<UUID, Long> liveRowCounts = rowCountResolver.resolve(List.of(saved));
        return toDTO(saved, liveRowCounts.get(saved.getId()));
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

    private AssetDTO toDTO(Asset asset, Long liveRowCount) {
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
            liveRowCount == null ? asset.getRowCount() : liveRowCount,
            asset.getSizeBytes(),
            parseColumns(asset.getColumns(), asset.getTenantId(), fqn),
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

    private List<AssetDTO.AssetColumnDTO> parseColumns(String raw, UUID tenantId, String fqn) {
        if (raw == null || raw.isBlank()) return Collections.emptyList();
        Map<String, List<AssetDTO.AssetColumnTermDTO>> termsByColumn = termsByColumn(tenantId, fqn);
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
                    item.path("suggestLevel").asText(null),
                    item.has("primaryKey") && item.path("primaryKey").asBoolean(false),
                    termsByColumn.getOrDefault(name.toLowerCase(Locale.ROOT), Collections.emptyList())
                ));
            });
            return columns;
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    private boolean matchesAsset(Asset asset, String query) {
        if (query == null || query.isBlank()) return false;
        return contains(asset.getOmFqn(), query)
            || contains(asset.getDisplayName(), query)
            || contains(asset.getDescription(), query)
            || contains(asset.getOwnerName(), query)
            || contains(asset.getTags(), query)
            || contains(asset.getColumns(), query);
    }

    private boolean contains(String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String cleanNullable(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private List<String> normalizeTags(List<String> tags) {
        if (tags == null) return Collections.emptyList();
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String tag : tags) {
            String value = cleanNullable(tag);
            if (value != null) {
                normalized.add(value);
            }
        }
        return new ArrayList<>(normalized);
    }

    private String mergeColumnMetadata(String existingColumns, List<AssetMetadataUpdateRequest.ColumnMetadataUpdateRequest> updates) {
        Map<String, AssetMetadataUpdateRequest.ColumnMetadataUpdateRequest> updateByName = new LinkedHashMap<>();
        for (AssetMetadataUpdateRequest.ColumnMetadataUpdateRequest update : updates) {
            if (update == null || cleanNullable(update.name()) == null) {
                throw new BizException(40070, "字段元数据必须包含字段名");
            }
            String key = update.name().trim().toLowerCase(Locale.ROOT);
            if (updateByName.put(key, update) != null) {
                throw new BizException(40070, "字段元数据重复: " + update.name());
            }
        }

        ArrayNode result = JsonUtil.mapper().createArrayNode();
        JsonNode existing = parseColumnArray(existingColumns);
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode item : existing) {
            if (!item.isObject()) {
                continue;
            }
            String name = item.path("name").asText("");
            if (name.isBlank()) {
                continue;
            }
            String key = name.toLowerCase(Locale.ROOT);
            seen.add(key);
            ObjectNode next = ((ObjectNode) item).deepCopy();
            AssetMetadataUpdateRequest.ColumnMetadataUpdateRequest update = updateByName.get(key);
            if (update != null) {
                putOptionalText(next, "description", update.description());
                putClassification(next, "classification", update.classification());
                putOptionalText(next, "piiType", update.piiType());
                putClassification(next, "suggestLevel", update.suggestLevel());
                if (update.primaryKey() == null || !update.primaryKey()) {
                    next.remove("primaryKey");
                } else {
                    next.put("primaryKey", true);
                }
            }
            result.add(next);
        }
        for (String requested : updateByName.keySet()) {
            if (!seen.contains(requested)) {
                throw new BizException(40070, "字段不存在，不能更新元数据: " + requested);
            }
        }
        return JsonUtil.toJson(result);
    }

    private JsonNode parseColumnArray(String raw) {
        if (raw == null || raw.isBlank()) {
            return JsonUtil.mapper().createArrayNode();
        }
        JsonNode node = JsonUtil.parse(raw);
        if (!node.isArray()) {
            throw new BizException(40070, "资产字段元数据格式异常");
        }
        return node;
    }

    private void putOptionalText(ObjectNode node, String field, String value) {
        String text = cleanNullable(value);
        if (text == null) {
            node.remove(field);
        } else {
            node.put(field, text);
        }
    }

    private void putClassification(ObjectNode node, String field, String value) {
        String normalized = cleanNullable(value);
        if (normalized == null) {
            node.remove(field);
            return;
        }
        String upper = normalized.toUpperCase(Locale.ROOT);
        if (!Set.of("L1", "L2", "L3", "L4").contains(upper)) {
            throw new BizException(40070, field + " 必须是 L1/L2/L3/L4");
        }
        node.put(field, upper);
    }

    private String maxColumnClassification(String columnsJson) {
        JsonNode node = parseColumnArray(columnsJson);
        String max = null;
        for (JsonNode item : node) {
            String classification = cleanNullable(item.path("classification").asText(null));
            if (classification == null) {
                continue;
            }
            String upper = classification.toUpperCase(Locale.ROOT);
            if (max == null || upper.compareTo(max) > 0) {
                max = upper;
            }
        }
        return max;
    }

    private Set<String> assetFqnsByTerm(UUID tenantId, String query) {
        if (query == null || query.isBlank()) return Collections.emptySet();
        try {
            String like = "%" + query.toLowerCase(Locale.ROOT) + "%";
            List<String> rows = jdbc.queryForList("""
                SELECT DISTINCT b.asset_fqn
                FROM modeling.business_term_binding b
                JOIN modeling.business_term t ON t.id = b.term_id AND t.tenant_id = b.tenant_id
                WHERE b.tenant_id = ?
                  AND b.status = 'ACTIVE'
                  AND (
                    lower(t.code) LIKE ?
                    OR lower(t.name) LIKE ?
                    OR lower(coalesce(t.definition, '')) LIKE ?
                    OR lower(coalesce(t.caliber_sql, '')) LIKE ?
                    OR lower(t.synonyms::text) LIKE ?
                  )
                """, String.class, tenantId, like, like, like, like, like);
            return rows == null ? Collections.emptySet() : new LinkedHashSet<>(rows);
        } catch (Exception ignored) {
            return Collections.emptySet();
        }
    }

    private Map<String, List<AssetDTO.AssetColumnTermDTO>> termsByColumn(UUID tenantId, String fqn) {
        if (tenantId == null || fqn == null || fqn.isBlank()) return Collections.emptyMap();
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT b.column_name, t.id, t.code, t.name, t.status
                FROM modeling.business_term_binding b
                JOIN modeling.business_term t ON t.id = b.term_id AND t.tenant_id = b.tenant_id
                WHERE b.tenant_id = ?
                  AND b.asset_fqn = ?
                  AND b.status = 'ACTIVE'
                ORDER BY b.column_name NULLS FIRST, t.code
                """, tenantId, fqn);
            if (rows == null || rows.isEmpty()) return Collections.emptyMap();
            Map<String, List<AssetDTO.AssetColumnTermDTO>> byColumn = new LinkedHashMap<>();
            for (Map<String, Object> row : rows) {
                Object column = row.get("column_name");
                if (column == null || String.valueOf(column).isBlank()) continue;
                String key = String.valueOf(column).toLowerCase(Locale.ROOT);
                Object id = row.get("id");
                byColumn.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new AssetDTO.AssetColumnTermDTO(
                    id instanceof UUID uuid ? uuid : UUID.fromString(String.valueOf(id)),
                    text(row.get("code")),
                    text(row.get("name")),
                    text(row.get("status"))
                ));
            }
            return byColumn;
        } catch (Exception ignored) {
            return Collections.emptyMap();
        }
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
