package com.onelake.orchestration.service;

import com.onelake.common.context.TenantContext;
import com.onelake.orchestration.domain.entity.Dag;
import com.onelake.orchestration.domain.enums.TriggerType;
import com.onelake.orchestration.repository.DagRepository;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 流水线周期调度触发器。
 *
 * <p>控制面使用 Spring {@code @Scheduled} 扫描已发布且 {@code schedule_cron}
 * 到期的流水线，并通过 {@code triggerPipelineRun} 触发运行。
 *
 * <p>这是最小实现版本；迁移到 Dagster 原生 schedule 延后处理。当前取舍：
 * <ul>
 *   <li>Spring {@code @Scheduled} 简单、留在控制面、无需 Dagster daemon 配置；
 *       代价是 cron 评估为“每分钟 tick + 比较”，不是完整调度器语义。</li>
 * </ul>
 *
 * <p><b>实现说明</b>：这里用 60 秒 tick 按分钟粒度评估 cron；cron 表达式由
 * Spring {@link org.springframework.scheduling.support.CronExpression} 解析。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PipelineSchedulerService {

    private static final String SCHEDULER_LOCK_KEY = "pipeline-scheduler";
    private static final Duration SCHEDULER_LOCK_TTL = Duration.ofMinutes(5);

    private final DagRepository dagRepo;
    private final SchedulerLockRepository schedulerLockRepo;
    private final OrchestrationService orchestrationService;

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

    private void triggerDuePipelines(Instant tickAt) {
        List<Dag> candidates;
        try {
            candidates = dagRepo.findByEnabledTrue();
        } catch (RuntimeException e) {
            log.warn("PipelineSchedulerService：加载启用 DAG 失败：{}", e.getMessage());
            return;
        }

        int triggered = 0;
        for (Dag dag : candidates) {
            try {
                ZoneId dagZone = ZoneId.of(dag.getTimezone() == null || dag.getTimezone().isBlank()
                        ? "Asia/Shanghai" : dag.getTimezone());
                ZonedDateTime nowZdt = tickAt.atZone(dagZone);
                ZonedDateTime prevMinuteStart = nowZdt.minusMinutes(1).withSecond(0).withNano(0);
                ZonedDateTime nowMinuteStart = nowZdt.withSecond(0).withNano(0);
                Optional<ZonedDateTime> scheduledAt = cronDueAt(dag, prevMinuteStart, nowMinuteStart);
                if (scheduledAt.isEmpty()) continue;
                // 仓储查询已过滤 enabled；此处再次防御，避免调用方/测试提供非启用记录时误触发。
                if (!Boolean.TRUE.equals(dag.getEnabled())) {
                    continue;
                }
                // 只触发已发布流水线。
                if (dag.getStatus() == null || !"PUBLISHED".equalsIgnoreCase(dag.getStatus())) {
                    continue;
                }
                // cron 调度只适用于统一 Spark 流水线作业。
                if (!"onelake_pipeline_run".equals(dag.getDagsterJob())) {
                    continue;
                }
                // 以流水线所属租户上下文运行。
                TenantContext.setTenantId(dag.getTenantId());
                try {
                    Instant scheduledInstant = scheduledAt.get().toInstant();
                    orchestrationService.triggerPipelineRun(
                            dag.getId(),
                            TriggerType.CRON,
                            scheduledInstant);
                    triggered++;
                    log.info("PipelineSchedulerService：已按 cron 触发流水线 {} (cron={}, scheduledAt={})",
                            dag.getId(), dag.getScheduleCron(), scheduledInstant);
                } finally {
                    TenantContext.clear();
                }
            } catch (DataIntegrityViolationException e) {
                // V14 的 uq_job_run_cron_logical 表示另一副本已为该 logical_date 创建 JobRun。
                log.info("PipelineSchedulerService：流水线 {} 在 logical_date 已触发，本轮跳过",
                        dag.getId());
            } catch (RuntimeException e) {
                log.warn("PipelineSchedulerService：触发流水线 {} 失败：{}",
                        dag.getId(), e.getMessage());
            }
        }
        if (triggered > 0) {
            log.info("PipelineSchedulerService：本轮 tick 触发 {} 条流水线", triggered);
        }
    }

    /**
     * 如果流水线 cron 在半开区间 {@code (prevMinuteStart, nowMinuteStart]} 内存在下一次触发点，
     * 则认为本轮 tick 应触发。
     */
    static boolean isCronDue(Dag dag, java.time.ZonedDateTime prevMinuteStart,
                              java.time.ZonedDateTime nowMinuteStart) {
        return cronDueAt(dag, prevMinuteStart, nowMinuteStart).isPresent();
    }

    /**
     * 返回半开区间 {@code (prevMinuteStart, nowMinuteStart]} 内的 cron 命中时间。
     */
    static Optional<ZonedDateTime> cronDueAt(Dag dag, ZonedDateTime prevMinuteStart,
                                             ZonedDateTime nowMinuteStart) {
        String cron = dag.getScheduleCron();
        if (cron == null || cron.isBlank()) return Optional.empty();
        try {
            org.springframework.scheduling.support.CronExpression expr =
                    org.springframework.scheduling.support.CronExpression.parse(cron);
            // next() 返回严格晚于入参时间的下一次匹配点。
            ZonedDateTime next = expr.next(prevMinuteStart);
            if (next != null && !next.isAfter(nowMinuteStart)) {
                return Optional.of(next);
            }
            return Optional.empty();
        } catch (IllegalArgumentException e) {
            log.debug("PipelineSchedulerService：流水线 {} 的 cron '{}' 非法：{}",
                    dag.getId(), cron, e.getMessage());
            return Optional.empty();
        }
    }
}
