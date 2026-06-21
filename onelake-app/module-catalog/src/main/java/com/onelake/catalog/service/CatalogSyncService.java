package com.onelake.catalog.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.onelake.catalog.client.OpenMetadataClient;
import com.onelake.catalog.domain.entity.Asset;
import com.onelake.catalog.repository.AssetRepository;
import com.onelake.common.context.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 目录同步服务（对应《技术初始化文档》§6.12）。
 * 定时/事件触发：把 OM 资产同步成本地索引，加速检索与影响分析。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CatalogSyncService {

    private final OpenMetadataClient om;
    private final AssetRepository assetRepo;

    @Transactional
    public int syncTables() {
        UUID tenantId = TenantContext.getTenantId();
        JsonNode page = om.listTables(200);
        int n = 0;
        for (JsonNode t : page.path("data")) {
            String fqn = t.path("fullyQualifiedName").asText();
            if (fqn.isBlank()) continue;
            Asset a = assetRepo.findByTenantIdAndOmFqn(tenantId, fqn)
                .orElseGet(Asset::new);
            a.setTenantId(tenantId);
            a.setOmFqn(fqn);
            a.setAssetType("TABLE");
            a.setLayer(layerOf(fqn));
            a.setDisplayName(t.path("displayName").asText(fqn));
            a.setDescription(t.path("description").asText(null));
            a.setOwnerName(ownerNameOf(t));
            a.setTags(com.onelake.common.util.JsonUtil.toJson(tagsOf(t)));
            a.setColumns(com.onelake.common.util.JsonUtil.toJson(columnsOf(t)));
            a.setClassification(extractClassification(t));
            a.setSyncedAt(Instant.now());
            assetRepo.save(a);
            n++;
        }
        log.info("catalog synced {} tables from OpenMetadata", n);
        return n;
    }

    private String ownerNameOf(JsonNode t) {
        JsonNode owner = t.path("owner");
        String displayName = owner.path("displayName").asText("");
        if (!displayName.isBlank()) return displayName;
        String name = owner.path("name").asText("");
        return name.isBlank() ? null : name;
    }

    private List<String> tagsOf(JsonNode t) {
        List<String> tags = new ArrayList<>();
        JsonNode node = t.path("tags");
        if (!node.isArray()) return tags;
        for (JsonNode tag : node) {
            String value = tag.path("tagFqn").asText(tag.path("name").asText(""));
            if (!value.isBlank()) tags.add(value);
        }
        return tags;
    }

    private List<Map<String, Object>> columnsOf(JsonNode t) {
        List<Map<String, Object>> columns = new ArrayList<>();
        JsonNode node = t.path("columns");
        if (!node.isArray()) return columns;
        for (JsonNode column : node) {
            String name = column.path("name").asText("");
            if (name.isBlank()) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", name);
            item.put("type", column.path("dataTypeDisplay").asText(column.path("dataType").asText("-")));
            String description = column.path("description").asText("");
            if (!description.isBlank()) item.put("description", description);
            columns.add(item);
        }
        return columns;
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

    /**
     * 从 OM tags 中提取最高密级（L1~L4），无标签返回 null。
     */
    private String extractClassification(JsonNode t) {
        JsonNode tags = t.path("tags");
        if (!tags.isArray() || tags.isEmpty()) return null;
        String best = null;
        for (JsonNode tag : tags) {
            String name = tag.path("tagFqn").asText(tag.path("name").asText(""));
            if (name.endsWith("L1")) best = higher(best, "L1");
            else if (name.endsWith("L2")) best = higher(best, "L2");
            else if (name.endsWith("L3")) best = higher(best, "L3");
            else if (name.endsWith("L4")) best = higher(best, "L4");
        }
        return best;
    }

    private String higher(String cur, String next) {
        if (cur == null) return next;
        return cur.compareTo(next) < 0 ? next : cur;
    }
}
