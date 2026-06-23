package com.onelake.catalog.repository;

import com.onelake.catalog.domain.entity.Asset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AssetRepository extends JpaRepository<Asset, UUID> {

    Optional<Asset> findByTenantIdAndOmFqn(UUID tenantId, String fqn);

    List<Asset> findByTenantIdAndLayer(UUID tenantId, String layer);

    List<Asset> findByTenantId(UUID tenantId);

    /**
     * 血缘图批量填充节点元数据时使用，避免 N+1。
     */
    List<Asset> findByTenantIdAndOmFqnIn(UUID tenantId, Collection<String> fqns);
}
