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
            a.setDisplayName(t.path("displayName").asText(fqn));
            a.setClassification(extractClassification(t));
            a.setSyncedAt(Instant.now());
            assetRepo.save(a);
            n++;
        }
        log.info("catalog synced {} tables from OpenMetadata", n);
        return n;
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
