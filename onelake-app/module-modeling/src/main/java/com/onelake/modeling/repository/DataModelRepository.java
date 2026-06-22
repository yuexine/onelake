package com.onelake.modeling.repository;

import com.onelake.modeling.domain.entity.DataModel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DataModelRepository extends JpaRepository<DataModel, UUID> {
    Optional<DataModel> findByTenantIdAndTargetFqn(UUID tenantId, String targetFqn);

    Optional<DataModel> findByIdAndTenantId(UUID id, UUID tenantId);

    List<DataModel> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    List<DataModel> findByTenantIdAndSourceFqnOrderByCreatedAtDesc(UUID tenantId, String sourceFqn);

    List<DataModel> findByTenantIdAndTargetFqnOrderByCreatedAtDesc(UUID tenantId, String targetFqn);
}
