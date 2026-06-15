package com.onelake.catalog.service;

import com.onelake.catalog.domain.entity.Asset;
import com.onelake.catalog.domain.entity.LineageEdge;
import com.onelake.catalog.repository.AssetRepository;
import com.onelake.catalog.repository.LineageEdgeRepository;
import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CatalogService {

    private final AssetRepository assetRepo;
    private final LineageEdgeRepository lineageRepo;

    @Transactional(readOnly = true)
    public Asset getAsset(UUID id) {
        return assetRepo.findById(id)
            .orElseThrow(() -> new BizException(40400, "资产不存在"));
    }

    @Transactional(readOnly = true)
    public List<Asset> listByLayer(String layer) {
        UUID tenantId = TenantContext.getTenantId();
        return layer == null || layer.isBlank()
            ? assetRepo.findByTenantId(tenantId)
            : assetRepo.findByTenantIdAndLayer(tenantId, layer.toUpperCase());
    }

    /** 影响分析：以下游 fqn 为根，BFS 找出所有下游资产。 */
    @Transactional(readOnly = true)
    public List<String> downstream(UUID tenantId, String rootFqn) {
        return lineageRepo.findByTenantIdAndUpstreamFqn(tenantId, rootFqn)
            .stream().map(LineageEdge::getDownstreamFqn).distinct().toList();
    }
}
