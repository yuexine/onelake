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

    /** 查询租户可见的全部 DAG。 */
    List<Dag> findByTenantId(UUID tenantId);

    /** 在租户边界内按 ID 查询 DAG。 */
    Optional<Dag> findByIdAndTenantId(UUID id, UUID tenantId);

    /**
     * 查询启用中的调度候选。
     *
     * <p>Repository 只下推 enabled 条件；PUBLISHED、统一 Dagster job、cron、窗口和
     * 日历条件由 PipelineSchedulerService 在 scheduler_lock 内统一判定。
     */
    List<Dag> findByEnabledTrue();
}
