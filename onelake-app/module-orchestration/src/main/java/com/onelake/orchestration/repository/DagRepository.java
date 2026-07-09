package com.onelake.orchestration.repository;

import com.onelake.orchestration.domain.entity.Dag;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * DAG 持久化访问接口。
 */
public interface DagRepository extends JpaRepository<Dag, UUID> {
    List<Dag> findByTenantId(UUID tenantId);

    Optional<Dag> findByIdAndTenantId(UUID id, UUID tenantId);

    /** P6-B：查询启用中的流水线，供调度器周期触发。 */
    List<Dag> findByEnabledTrue();
}
