package com.onelake.orchestration.service;

import com.onelake.common.context.TenantContext;
import com.onelake.orchestration.domain.entity.Dag;
import com.onelake.orchestration.domain.enums.TriggerType;
import com.onelake.orchestration.repository.DagRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

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

    private final DagRepository dagRepo;
    private final OrchestrationService orchestrationService;

    /**
     * 每 60 秒触发一次，按当前时间评估已发布流水线是否到期。
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void tickScheduledPipelines() {
        List<Dag> candidates;
        try {
            candidates = dagRepo.findByEnabledTrue();
        } catch (RuntimeException e) {
            log.warn("PipelineSchedulerService：加载启用 DAG 失败：{}", e.getMessage());
            return;
        }

        java.time.ZonedDateTime nowZdt = java.time.Instant.now().atZone(java.time.ZoneId.systemDefault());
        java.time.ZonedDateTime prevMinuteStart = nowZdt.minusMinutes(1).withSecond(0).withNano(0);
        java.time.ZonedDateTime nowMinuteStart = nowZdt.withSecond(0).withNano(0);

        int triggered = 0;
        for (Dag dag : candidates) {
            try {
                if (!isCronDue(dag, prevMinuteStart, nowMinuteStart)) continue;
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
                    orchestrationService.triggerPipelineRun(dag.getId(), TriggerType.CRON);
                    triggered++;
                    log.info("PipelineSchedulerService：已按 cron 触发流水线 {} (cron={})",
                            dag.getId(), dag.getScheduleCron());
                } finally {
                    TenantContext.clear();
                }
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
        String cron = dag.getScheduleCron();
        if (cron == null || cron.isBlank()) return false;
        try {
            org.springframework.scheduling.support.CronExpression expr =
                    org.springframework.scheduling.support.CronExpression.parse(cron);
            // next() 返回严格晚于入参时间的下一次匹配点。
            java.time.ZonedDateTime next = expr.next(prevMinuteStart);
            return next != null && !next.isAfter(nowMinuteStart);
        } catch (IllegalArgumentException e) {
            log.debug("PipelineSchedulerService：流水线 {} 的 cron '{}' 非法：{}",
                    dag.getId(), cron, e.getMessage());
            return false;
        }
    }
}
