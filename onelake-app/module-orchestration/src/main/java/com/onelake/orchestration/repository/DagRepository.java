package com.onelake.orchestration.repository;

import com.onelake.orchestration.domain.entity.Dag;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * DAG 持久化访问接口。
 */
public interface DagRepository extends JpaRepository<Dag, UUID> {

    /**
     * 锁定 DAG，串行化同一 DAG 下自动重跑的活跃数检查和新运行创建。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Dag d where d.id = :id")
    Optional<Dag> findByIdForUpdate(@Param("id") UUID id);

    /** 查询租户可见的全部 DAG。 */
    List<Dag> findByTenantId(UUID tenantId);

    /** 在租户边界内按 ID 查询 DAG。 */
    Optional<Dag> findByIdAndTenantId(UUID id, UUID tenantId);

    /**
     * 仅更新调度策略列，避免调度抽屉与画布/发布状态并发保存时覆盖彼此字段。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Dag d
               set d.timezone = :timezone,
                   d.catchup = :catchup,
                   d.maxActiveRuns = :maxActiveRuns,
                   d.priority = :priority,
                   d.scheduleMode = :scheduleMode,
                   d.slaMinutes = :slaMinutes,
                   d.timeoutMinutes = :timeoutMinutes,
                   d.runRetryCount = :runRetryCount,
                   d.runRetryIntervalSeconds = :runRetryIntervalSeconds,
                   d.calendarId = :calendarId,
                   d.scheduleStart = :scheduleStart,
                   d.scheduleEnd = :scheduleEnd
             where d.id = :id
               and d.tenantId = :tenantId
            """)
    int updateSchedulingPolicy(@Param("id") UUID id,
                               @Param("tenantId") UUID tenantId,
                               @Param("timezone") String timezone,
                               @Param("catchup") boolean catchup,
                               @Param("maxActiveRuns") int maxActiveRuns,
                               @Param("priority") int priority,
                               @Param("scheduleMode") String scheduleMode,
                               @Param("slaMinutes") Integer slaMinutes,
                               @Param("timeoutMinutes") Integer timeoutMinutes,
                               @Param("runRetryCount") int runRetryCount,
                               @Param("runRetryIntervalSeconds") int runRetryIntervalSeconds,
                               @Param("calendarId") UUID calendarId,
                               @Param("scheduleStart") Instant scheduleStart,
                               @Param("scheduleEnd") Instant scheduleEnd);

    /**
     * 查询启用中的调度候选。
     *
     * <p>Repository 只下推 enabled 条件；PUBLISHED、统一 Dagster job、cron、窗口和
     * 日历条件由 PipelineSchedulerService 在 scheduler_lock 内统一判定。
     */
    List<Dag> findByEnabledTrue();
}
