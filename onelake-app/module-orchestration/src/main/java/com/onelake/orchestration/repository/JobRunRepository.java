package com.onelake.orchestration.repository;

import com.onelake.orchestration.domain.entity.JobRun;
import com.onelake.orchestration.domain.enums.DagStatus;
import com.onelake.orchestration.domain.enums.TriggerType;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * DAG 运行实例持久化访问接口。
 */
public interface JobRunRepository extends JpaRepository<JobRun, UUID> {

    /** 按实际启动时间倒序分页查询某 DAG 的运行历史。 */
    Page<JobRun> findByDagIdOrderByStartedAtDesc(UUID dagId, Pageable pageable);

    /** 按实际启动时间倒序分页查询一组租户可见 DAG 的运行历史。 */
    Page<JobRun> findByDagIdInOrderByStartedAtDesc(Collection<UUID> dagIds, Pageable pageable);

    /** 按 logical_date 升序分页查询一个回填批次产生的 JobRun。 */
    Page<JobRun> findByDagIdAndBackfillIdOrderByLogicalDateAsc(UUID dagId,
                                                               UUID backfillId,
                                                               Pageable pageable);

    /** 在调用方已完成租户 DAG 过滤后读取运行详情。 */
    Optional<JobRun> findByIdAndDagIdIn(UUID id, Collection<UUID> dagIds);

    /** 用 DAG 和 backfill 双边界读取回填运行，防止批次间串查。 */
    Optional<JobRun> findByIdAndDagIdAndBackfillId(UUID id, UUID dagId, UUID backfillId);

    /** 锁定 JobRun，供取消、状态同步和重跑状态机执行原子变更。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select jr from JobRun jr where jr.id = :id")
    Optional<JobRun> findByIdForUpdate(@Param("id") UUID id);

    /** 在租户可见 DAG 范围内锁定 JobRun。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select jr from JobRun jr where jr.id = :id and jr.dagId in :dagIds")
    Optional<JobRun> findByIdAndDagIdInForUpdate(@Param("id") UUID id,
                                                 @Param("dagIds") Collection<UUID> dagIds);

    /** 查询实际启动时间最新的生产运行，排除 DEV 试跑，供流水线列表摘要展示。 */
    Optional<JobRun> findFirstByDagIdAndRunModeNotOrderByStartedAtDesc(
            UUID dagId, String excludedRunMode);

    /**
     * 查询 DAG 最新的生产业务周期运行，排除调用方指定的 DEV 试跑模式。
     *
     * <p>只考虑已经写入 {@code logical_date} 的运行，供 {@code CatchupPlanner}
     * 确定历史补跑的起始游标；按业务周期而不是实际启动时间排序，避免迟到回填干扰判断。
     */
    Optional<JobRun> findFirstByDagIdAndLogicalDateIsNotNullAndRunModeNotOrderByLogicalDateDesc(
            UUID dagId, String excludedRunMode);

    /**
     * 统计指定 DAG 处于给定状态集合的生产运行数量，排除 DEV 试跑。
     *
     * <p>C3 调度器和 M1 回填派发器共同使用该查询实施 DAG 级
     * {@code max_active_runs} 限流。
     */
    long countByDagIdAndStatusInAndRunModeNot(UUID dagId,
                                               Collection<DagStatus> statuses,
                                               String excludedRunMode);

    /** 指定上游流水线在目标业务周期是否已有生产成功运行；DRY_RUN 命中，DEV 不命中。 */
    boolean existsByDagIdAndLogicalDateAndStatusAndRunModeNot(UUID dagId,
                                                               Instant logicalDate,
                                                               DagStatus status,
                                                               String excludedRunMode);

    /** 判断指定 CRON 计划点是否已经生成运行，供依赖等待队列做唯一键幂等收口。 */
    boolean existsByDagIdAndLogicalDateAndTriggerType(UUID dagId,
                                                       Instant logicalDate,
                                                       TriggerType triggerType);

    /** 查询资产事件确定性触发键对应的既有运行，供回执失败后的重试复用。 */
    Optional<JobRun> findByDagIdAndEventTriggerKey(UUID dagId, String eventTriggerKey);

    /**
     * 返回已经到达 DAG 重跑间隔且尚未领取的生产失败运行 ID；DEV 试跑不自动重跑。
     *
     * <p>到期条件直接在 PostgreSQL 查询中计算，避免长间隔旧记录反复占据固定分页并
     * 饿死后续已到期候选；服务层仍会在加锁后重判策略和并发。</p>
     */
    @Query(value = """
            select jr.id
            from orchestration.job_run jr
            join orchestration.dag d on d.id = jr.dag_id
            where jr.status = 'FAILED'
              and upper(jr.run_mode) <> 'DEV'
              and jr.retry_dispatched_at is null
              and jr.run_retry_attempt < d.run_retry_count
              and upper(d.schedule_mode) <> 'FROZEN'
              and coalesce(jr.finished_at, jr.updated_at, jr.started_at)
                    + (d.run_retry_interval_seconds * interval '1 second') <= :now
            order by jr.finished_at, jr.id
            """, nativeQuery = true)
    List<UUID> findRetryCandidateIds(@Param("now") Instant now, Pageable pageable);

    /** 后台需要主动同步的全部非终态 Dagster 运行，包括未配置自动重跑的 EVENT 运行。 */
    @Query(value = """
            select jr.id
            from orchestration.job_run jr
            where jr.status in ('QUEUED', 'RUNNING')
              and jr.dagster_run_id is not null
            order by coalesce(jr.updated_at, jr.started_at), jr.id
            """, nativeQuery = true)
    List<UUID> findActiveDagsterRunIds(Pageable pageable);

    /**
     * 查询需要 SLA/超时巡检的非终态运行；DEV 只保留 timeout 保护，不产生生产 SLA 告警。
     *
     * <p>已标记 SLA 违约且未配置 timeout 的运行不再进入候选集合；配置了 timeout 的
     * 运行会持续被检查，直至超时取消或由正常状态同步收口为终态。</p>
     */
    @Query("""
            select jr.id
            from JobRun jr, Dag d
            where d.id = jr.dagId
              and jr.status in :statuses
              and jr.startedAt is not null
              and (d.timeoutMinutes is not null
                   or (upper(jr.runMode) <> 'DEV'
                       and d.slaMinutes is not null
                       and jr.slaMissed = false))
            order by jr.startedAt, jr.id
            """)
    List<UUID> findSlaMonitorCandidateIds(@Param("statuses") Collection<DagStatus> statuses);

    /** 查询某次失败运行直接创建的最新自动重跑，用于回填队列沿重跑链推进。 */
    Optional<JobRun> findFirstByRetrySourceRunIdOrderByStartedAtDesc(UUID retrySourceRunId);
}
