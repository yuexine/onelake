package com.onelake.modeling.repository;

import com.onelake.modeling.domain.entity.BusinessTermBinding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BusinessTermBindingRepository extends JpaRepository<BusinessTermBinding, UUID> {
    List<BusinessTermBinding> findByTenantIdAndTermIdOrderByCreatedAtDesc(UUID tenantId, UUID termId);

    List<BusinessTermBinding> findByTenantIdAndAssetFqnAndStatusOrderByColumnNameAsc(UUID tenantId, String assetFqn, String status);

    long countByTenantIdAndTermIdAndStatus(UUID tenantId, UUID termId, String status);

    Optional<BusinessTermBinding> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<BusinessTermBinding> findByTenantIdAndTermIdAndAssetFqnAndColumnName(UUID tenantId, UUID termId, String assetFqn, String columnName);
}
