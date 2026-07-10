package com.onelake.orchestration.service;

import com.onelake.common.context.TenantContext;
import com.onelake.orchestration.domain.entity.Dag;
import com.onelake.orchestration.domain.entity.PipelineDependencyWait;
import com.onelake.orchestration.domain.entity.ScheduleCalendarDay;
import com.onelake.orchestration.domain.entity.ScheduleCalendarDayId;
import com.onelake.orchestration.domain.enums.DagStatus;
import com.onelake.orchestration.domain.enums.ScheduleMode;
import com.onelake.orchestration.domain.enums.TriggerType;
import com.onelake.orchestration.repository.DagRepository;
import com.onelake.orchestration.repository.JobRunRepository;
import com.onelake.orchestration.repository.PipelineDependencyWaitRepository;
import com.onelake.orchestration.repository.ScheduleCalendarDayRepository;
import com.onelake.orchestration.repository.SchedulerLockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 流水线周期调度触发器。
 *
 * <p>控制面使用 Spring {@code @Scheduled} 扫描已发布且 {@code schedule_cron}
 * 到期的流水线，并通过 {@code triggerPipelineRun} 触发运行。
 *
 * <p>这里用 60 秒 tick 按 DAG 时区评估 cron，并在同一 scheduler lock 内完成
 * 调度窗口、日历、冻结、并发与 catchup 判定。当前周期继续走 CRON 唯一键幂等，
 * 历史缺口进入 M1 回填队列。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PipelineSchedulerService {

    /** 多副本共享的全局调度锁键；同一时刻只允许一个实例扫描并触发到期 DAG。 */
    private static final String SCHEDULER_LOCK_KEY = "pipeline-scheduler";
    /** 防止实例异常退出后永久占锁；过期锁可由下一实例接管。 */
    private static final Duration SCHEDULER_LOCK_TTL = Duration.ofMinutes(5);
    /** JobRun 统一状态中仅 QUEUED/RUNNING 属于非终态，占用 DAG 并发槽位。 */
    private static final List<DagStatus> ACTIVE_RUN_STATUSES =
            List.of(DagStatus.QUEUED, DagStatus.RUNNING);

    /** 提供启用 DAG 的调度候选集合。 */
    private final DagRepository dagRepo;
    /** 提供数据库级 scheduler_lock 的抢占与释放。 */
    private final SchedulerLockRepository schedulerLockRepo;
    /** 负责当前周期的 CRON JobRun 创建、编译与 Dagster 启动。 */
    private final OrchestrationService orchestrationService;
    /** 提供 DAG 级非终态运行计数。 */
    private final JobRunRepository jobRunRepo;
    /** 查询绑定调度日历中的具体日期类型。 */
    private final ScheduleCalendarDayRepository calendarDayRepo;
    /** 计算当前周期之前尚未覆盖的历史业务周期。 */
    private final CatchupPlanner catchupPlanner;
    /** 将 catchup 周期交给 M1 持久化回填队列。 */
    private final BackfillDispatcher backfillDispatcher;
    /** 判定当前 logical_date 的同周期/跨周期上游是否全部成功。 */
    private final DependencyReadinessService dependencyReadinessService;
    /** 与触发入口复用同一业务区间算法，避免计划点和 logical_date 口径漂移。 */
    private final DataIntervalCalculator dataIntervalCalculator;
    /** 持久化被依赖阻塞的计划点，使下一 tick 能脱离 cron 窗口继续重判。 */
    private final PipelineDependencyWaitRepository dependencyWaitRepo;

    /**
     * 每 60 秒触发一次，按当前时间评估已发布流水线是否到期。
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void tickScheduledPipelines() {
        tickScheduledPipelines(Instant.now());
    }

    /**
     * 执行单次调度 tick。使用参数化时间便于固定 cron 命中分钟的幂等键并进行单元测试。
     */
    void tickScheduledPipelines(Instant tickAt) {
        String lockHolder = UUID.randomUUID().toString();
        boolean lockAcquired;
        try {
            // acquire 由数据库原子 upsert 实现；只有返回 1 的实例拥有本轮调度权。
            lockAcquired = schedulerLockRepo.acquire(
                    SCHEDULER_LOCK_KEY, lockHolder, tickAt.plus(SCHEDULER_LOCK_TTL)) == 1;
        } catch (RuntimeException e) {
            log.warn("PipelineSchedulerService：获取调度锁失败，本轮跳过：{}", e.getMessage());
            return;
        }
        if (!lockAcquired) {
            log.debug("PipelineSchedulerService：调度锁正由其他实例持有，本轮跳过");
            return;
        }

        try {
            retryDependencyWaits();
            triggerDuePipelines(tickAt);
        } finally {
            try {
                int released = schedulerLockRepo.release(SCHEDULER_LOCK_KEY, lockHolder);
                if (released == 0) {
                    log.warn("PipelineSchedulerService：调度锁已过期或被接管，未释放当前 holder 的锁");
                }
            } catch (RuntimeException e) {
                log.warn("PipelineSchedulerService：释放调度锁失败：{}", e.getMessage());
            }
        }
    }

    /**
     * 扫描、排序并触发本轮到期流水线。
     *
     * <p>单 DAG 的处理顺序固定为：基础资格 → cron 命中 → 调度窗口 → 日历 →
     * FROZEN → 上游依赖 → max_active_runs → catchup 规划 → 当前周期 → 历史回填。
     * 所有需要租户数据的操作均在对应 DAG 的租户上下文内执行。
     */
    private void triggerDuePipelines(Instant tickAt) {
        List<Dag> candidates;
        try {
            candidates = dagRepo.findByEnabledTrue();
        } catch (RuntimeException e) {
            log.warn("PipelineSchedulerService：加载启用 DAG 失败：{}", e.getMessage());
            return;
        }

        // 数值越大优先级越高；稳定排序保证同优先级候选保持仓储返回顺序。
        List<Dag> prioritized = candidates.stream()
                .sorted(Comparator.comparingInt(PipelineSchedulerService::priorityOf).reversed())
                .toList();
        // 固定为完整分钟边界，保证不同实例对同一 tick 计算出相同的计划时刻和幂等键。
        Instant nowMinuteStart = tickAt.truncatedTo(ChronoUnit.MINUTES);
        Instant prevMinuteStart = nowMinuteStart.minus(1, ChronoUnit.MINUTES);
        int triggered = 0;
        for (Dag dag : prioritized) {
            try {
                if (!isSchedulablePipeline(dag)) {
                    continue;
                }
                Optional<ZonedDateTime> scheduledAt = cronDueAt(dag, prevMinuteStart, nowMinuteStart);
                if (scheduledAt.isEmpty()) {
                    continue;
                }

                // 从命中 cron 开始切换到 DAG 所属租户；finally 中无条件清理，避免线程复用串租户。
                TenantContext.setTenantId(dag.getTenantId());
                Instant scheduledInstant = scheduledAt.get().toInstant();
                // C3 要求的过滤顺序：窗口 → 日历 → 冻结。后置条件不得提前创建 JobRun。
                if (!isInsideScheduleWindow(dag, scheduledInstant)) {
                    log.debug("PipelineSchedulerService：流水线 {} 命中 cron 但不在调度窗口内", dag.getId());
                    continue;
                }
                if (isHoliday(dag, scheduledAt.get())) {
                    log.info("PipelineSchedulerService：流水线 {} 命中 HOLIDAY，跳过 {}",
                            dag.getId(), scheduledAt.get().toLocalDate());
                    continue;
                }
                if (ScheduleMode.from(dag.getScheduleMode()) == ScheduleMode.FROZEN) {
                    log.info("PipelineSchedulerService：流水线 {} 已冻结，本周期跳过", dag.getId());
                    continue;
                }

                Instant logicalDate = dataIntervalCalculator.calculate(
                        dag.getPartitionGrain(), scheduledInstant, dag.getTimezone()).logicalDate();
                DependencyReadinessService.ReadinessResult readiness =
                        dependencyReadinessService.evaluate(dag, logicalDate);
                if (!readiness.ready()) {
                    dependencyWaitRepo.enqueue(
                            dag.getTenantId(), dag.getId(), logicalDate, scheduledInstant);
                    log.info("PipelineSchedulerService：流水线 {} 等待上游/被阻塞，logicalDate={} blockers={}",
                            dag.getId(), logicalDate, readiness.summary());
                    continue;
                }

                int maxActiveRuns = Math.max(1, dag.getMaxActiveRuns() == null ? 1 : dag.getMaxActiveRuns());
                long activeRuns = jobRunRepo.countByDagIdAndStatusIn(dag.getId(), ACTIVE_RUN_STATUSES);
                if (activeRuns >= maxActiveRuns) {
                    // 不创建第二条并发运行；记录计划点和策略，供运维追查 misfire。
                    log.warn("PipelineSchedulerService：misfire dagId={} logicalDate={} activeRuns={} "
                                    + "maxActiveRuns={} policy={}",
                            dag.getId(), scheduledInstant, activeRuns, maxActiveRuns, dag.getMisfirePolicy());
                    continue;
                }

                CatchupPlanner.CatchupPlan catchupPlan = CatchupPlanner.CatchupPlan.empty();
                if (Boolean.TRUE.equals(dag.getCatchup())) {
                    try {
                        // 必须在创建当前 JobRun 前取 last logical_date，否则当前周期会掩盖历史缺口。
                        catchupPlan = catchupPlanner.plan(dag, scheduledInstant);
                    } catch (RuntimeException e) {
                        log.warn("PipelineSchedulerService：流水线 {} catchup 规划失败，继续当前周期：{}",
                                dag.getId(), e.getMessage());
                    }
                }

                try {
                    // 先触发当前周期：V14 唯一索引负责幂等，同时该 JobRun 会先占用并发槽位，
                    // 随后的 M1 回填派发便不会与当前周期合计突破 max_active_runs。
                    orchestrationService.triggerPipelineRun(
                            dag.getId(),
                            TriggerType.CRON,
                            scheduledInstant);
                    triggered++;
                    log.info("PipelineSchedulerService：已按 cron 触发流水线 {} (cron={}, scheduledAt={})",
                            dag.getId(), dag.getScheduleCron(), scheduledInstant);
                } catch (DataIntegrityViolationException e) {
                    // V14 的 uq_job_run_cron_logical 表示另一副本已为该 logical_date 创建 JobRun。
                    log.info("PipelineSchedulerService：流水线 {} 在 logical_date 已触发，本轮跳过",
                            dag.getId());
                    continue;
                }

                if (!catchupPlan.isEmpty()) {
                    try {
                        // 回填批次保留精确周期列表，不把稀疏 cron 误展开为连续日期。
                        UUID backfillId = backfillDispatcher.dispatchCatchup(
                                dag.getId(),
                                catchupPlan.windows(),
                                dag.getPartitionGrain(),
                                maxActiveRuns);
                        log.info("PipelineSchedulerService：流水线 {} 已提交 catchup backfillId={} periods={}",
                                dag.getId(), backfillId, catchupPlan.size());
                    } catch (RuntimeException e) {
                        log.warn("PipelineSchedulerService：流水线 {} 当前周期已触发，但 catchup 提交失败：{}",
                                dag.getId(), e.getMessage());
                    }
                }
            } catch (RuntimeException e) {
                log.warn("PipelineSchedulerService：触发流水线 {} 失败：{}",
                        dag.getId(), e.getMessage());
            } finally {
                TenantContext.clear();
            }
        }
        if (triggered > 0) {
            log.info("PipelineSchedulerService：本轮 tick 触发 {} 条流水线", triggered);
        }
    }

    /**
     * 重判之前 tick 因依赖未就绪而保存的计划点。
     *
     * <p>等待记录使用原始 {@code scheduledAt} 触发，确保生成的 logical_date 与首次
     * cron 命中一致；成功或唯一键确认已触发后才删除，其他异常保留到下一 tick。</p>
     */
    private void retryDependencyWaits() {
        List<PipelineDependencyWait> waits;
        try {
            waits = dependencyWaitRepo.findAllByOrderByCreatedAtAscIdAsc();
        } catch (RuntimeException e) {
            log.warn("PipelineSchedulerService：加载依赖等待计划点失败：{}", e.getMessage());
            return;
        }

        for (PipelineDependencyWait wait : waits) {
            try {
                Optional<Dag> dagOptional = dagRepo.findByIdAndTenantId(
                        wait.getDagId(), wait.getTenantId());
                if (dagOptional.isEmpty()) {
                    dependencyWaitRepo.deleteById(wait.getId());
                    continue;
                }
                Dag dag = dagOptional.get();
                if (!isSchedulablePipeline(dag)
                        || ScheduleMode.from(dag.getScheduleMode()) == ScheduleMode.FROZEN) {
                    continue;
                }

                TenantContext.setTenantId(dag.getTenantId());
                DependencyReadinessService.ReadinessResult readiness =
                        dependencyReadinessService.evaluate(dag, wait.getLogicalDate());
                if (!readiness.ready()) {
                    log.debug("PipelineSchedulerService：流水线 {} 仍等待上游，logicalDate={} blockers={}",
                            dag.getId(), wait.getLogicalDate(), readiness.summary());
                    continue;
                }

                int maxActiveRuns = Math.max(1,
                        dag.getMaxActiveRuns() == null ? 1 : dag.getMaxActiveRuns());
                long activeRuns = jobRunRepo.countByDagIdAndStatusIn(
                        dag.getId(), ACTIVE_RUN_STATUSES);
                if (activeRuns >= maxActiveRuns) {
                    continue;
                }

                try {
                    orchestrationService.triggerPipelineRun(
                            dag.getId(), TriggerType.CRON, wait.getScheduledAt());
                    dependencyWaitRepo.deleteById(wait.getId());
                    log.info("PipelineSchedulerService：依赖已就绪，恢复触发流水线 {} logicalDate={}",
                            dag.getId(), wait.getLogicalDate());
                } catch (DataIntegrityViolationException e) {
                    // 只把 CRON 唯一键冲突视为成功；其他完整性错误保留等待记录供后续修复重试。
                    boolean alreadyTriggered = jobRunRepo.existsByDagIdAndLogicalDateAndTriggerType(
                            dag.getId(), wait.getLogicalDate(), TriggerType.CRON);
                    if (!alreadyTriggered) {
                        throw e;
                    }
                    dependencyWaitRepo.deleteById(wait.getId());
                    log.info("PipelineSchedulerService：依赖等待计划点已由其他路径触发，清理 dagId={} logicalDate={}",
                            dag.getId(), wait.getLogicalDate());
                }
            } catch (RuntimeException e) {
                log.warn("PipelineSchedulerService：恢复依赖等待计划点 {} 失败：{}",
                        wait.getId(), e.getMessage());
            } finally {
                TenantContext.clear();
            }
        }
    }

    /**
     * 如果流水线 cron 在半开区间 {@code (prevMinuteStart, nowMinuteStart]} 内存在下一次触发点，
     * 则认为本轮 tick 应触发。
     */
    static boolean isCronDue(Dag dag, java.time.ZonedDateTime prevMinuteStart,
                              java.time.ZonedDateTime nowMinuteStart) {
        return isCronDue(dag, prevMinuteStart.toInstant(), nowMinuteStart.toInstant());
    }

    static boolean isCronDue(Dag dag, Instant prevMinuteStart, Instant nowMinuteStart) {
        return cronDueAt(dag, prevMinuteStart, nowMinuteStart).isPresent();
    }

    /**
     * 返回半开区间 {@code (prevMinuteStart, nowMinuteStart]} 内的 cron 命中时间。
     */
    static Optional<ZonedDateTime> cronDueAt(Dag dag, ZonedDateTime prevMinuteStart,
                                             ZonedDateTime nowMinuteStart) {
        return cronDueAt(dag, prevMinuteStart.toInstant(), nowMinuteStart.toInstant());
    }

    static Optional<ZonedDateTime> cronDueAt(Dag dag, Instant prevMinuteStart,
                                             Instant nowMinuteStart) {
        String cron = dag.getScheduleCron();
        if (cron == null || cron.isBlank()) return Optional.empty();
        try {
            // CronExpression 直接使用 DAG 的业务时区，禁止受应用服务器默认时区影响。
            ZoneId dagZone = ZoneId.of(dag.getTimezone());
            org.springframework.scheduling.support.CronExpression expr =
                    org.springframework.scheduling.support.CronExpression.parse(cron);
            // next() 返回严格晚于入参时间的下一次匹配点。
            ZonedDateTime next = expr.next(prevMinuteStart.atZone(dagZone));
            if (next != null && !next.toInstant().isAfter(nowMinuteStart)) {
                return Optional.of(next);
            }
            return Optional.empty();
        } catch (RuntimeException e) {
            log.debug("PipelineSchedulerService：流水线 {} 的 cron/timezone 配置非法 (cron='{}', timezone='{}')：{}",
                    dag.getId(), cron, dag.getTimezone(), e.getMessage());
            return Optional.empty();
        }
    }

    /** 查询计划点在 DAG 本地日期上是否命中 HOLIDAY。未绑定日历或无覆盖记录即正常调度。 */
    private boolean isHoliday(Dag dag, ZonedDateTime scheduledAt) {
        if (dag.getCalendarId() == null) {
            return false;
        }
        ScheduleCalendarDayId dayId = new ScheduleCalendarDayId(
                dag.getCalendarId(), scheduledAt.toLocalDate());
        return calendarDayRepo.findById(dayId)
                .map(ScheduleCalendarDay::getDayType)
                .filter("HOLIDAY"::equalsIgnoreCase)
                .isPresent();
    }

    /** schedule_start/end 均按闭区间处理。 */
    private boolean isInsideScheduleWindow(Dag dag, Instant scheduledAt) {
        return (dag.getScheduleStart() == null || !scheduledAt.isBefore(dag.getScheduleStart()))
                && (dag.getScheduleEnd() == null || !scheduledAt.isAfter(dag.getScheduleEnd()));
    }

    /** 保留原调度入口约束：仅启用、已发布且使用统一流水线 Job 的 DAG 可进入 cron 调度。 */
    private boolean isSchedulablePipeline(Dag dag) {
        return Boolean.TRUE.equals(dag.getEnabled())
                && "PUBLISHED".equalsIgnoreCase(dag.getStatus())
                && "onelake_pipeline_run".equals(dag.getDagsterJob());
    }

    /** 空优先级兼容 V16 默认值 5。 */
    private static int priorityOf(Dag dag) {
        return dag.getPriority() == null ? 5 : dag.getPriority();
    }
}
