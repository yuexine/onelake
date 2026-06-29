package com.onelake.analytics.repository;

import com.onelake.analytics.domain.entity.Dataset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DatasetRepository extends JpaRepository<Dataset, UUID> {

    Optional<Dataset> findByIdAndTenantId(UUID id, UUID tenantId);

    List<Dataset> findByTenantId(UUID tenantId);

    Optional<Dataset> findByTenantIdAndName(UUID tenantId, String name);

    boolean existsByTenantIdAndName(UUID tenantId, String name);
}
