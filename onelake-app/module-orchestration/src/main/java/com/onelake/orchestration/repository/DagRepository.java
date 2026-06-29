package com.onelake.orchestration.repository;

import com.onelake.orchestration.domain.entity.Dag;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DagRepository extends JpaRepository<Dag, UUID> {
    List<Dag> findByTenantId(UUID tenantId);

    Optional<Dag> findByIdAndTenantId(UUID id, UUID tenantId);

    /** P6-B: enabled pipelines for scheduled triggering. */
    List<Dag> findByEnabledTrue();
}
