package com.onelake.modeling.repository;

import com.onelake.modeling.domain.entity.DataModelRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DataModelRunRepository extends JpaRepository<DataModelRun, UUID> {
    Optional<DataModelRun> findByIdAndTenantId(UUID id, UUID tenantId);

    List<DataModelRun> findByModelIdAndTenantIdOrderByQueuedAtDesc(UUID modelId, UUID tenantId);

    boolean existsByModelIdAndSourceIntegrationRunId(UUID modelId, UUID sourceIntegrationRunId);

    boolean existsByModelIdAndStatusIn(UUID modelId, Collection<String> statuses);
}
