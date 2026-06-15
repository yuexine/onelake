package com.onelake.catalog.repository;

import com.onelake.catalog.domain.entity.Asset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AssetRepository extends JpaRepository<Asset, UUID> {

    Optional<Asset> findByTenantIdAndOmFqn(UUID tenantId, String fqn);

    List<Asset> findByTenantIdAndLayer(UUID tenantId, String layer);

    List<Asset> findByTenantId(UUID tenantId);
}
