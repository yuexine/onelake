package com.onelake.orchestration.repository;

import com.onelake.orchestration.domain.entity.Dag;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DagRepository extends JpaRepository<Dag, UUID> {
    List<Dag> findByTenantId(UUID tenantId);
}
