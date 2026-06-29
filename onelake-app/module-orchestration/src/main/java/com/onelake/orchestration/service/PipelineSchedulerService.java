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
 * P6-B: scheduled pipeline triggering.
 *
 * <p>Implements §6.5 of the design doc — "control-plane Spring {@code @Scheduled} scans
 * PUBLISHED pipelines whose {@code schedule_cron} is due → trigger via {@code triggerPipelineRun}".
 *
 * <p>This is the <b>minimal implementation</b> the design doc specifies; migration to
 * Dagster native schedules is deferred (P6+). Trade-off:
 * <ul>
 *   <li>Spring {@code @Scheduled} pros: simpler, lives in control plane, no Dagster daemon
 *       config needed. Cons: cron evaluation is "every 1 min tick + compare", not real cron.</li>
 * </ul>
 *
 * <p><b>Implementation note</b>: the design doc's "scan due PUBLISHED pipelines" is realized
 * here as a 60s tick that evaluates each pipeline's cron against current time (minute-granularity).
 * Cron expressions are interpreted via Spring's {@link org.springframework.scheduling.support.CronExpression}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PipelineSchedulerService {

    private final DagRepository dagRepo;
    private final OrchestrationService orchestrationService;

    /**
     * Tick every 60 seconds; evaluate each PUBLISHED pipeline's cron against current time.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void tickScheduledPipelines() {
        List<Dag> candidates;
        try {
            candidates = dagRepo.findByEnabledTrue();
        } catch (RuntimeException e) {
            log.warn("PipelineSchedulerService: failed to load enabled DAGs: {}", e.getMessage());
            return;
        }

        java.time.ZonedDateTime nowZdt = java.time.Instant.now().atZone(java.time.ZoneId.systemDefault());
        java.time.ZonedDateTime prevMinuteStart = nowZdt.minusMinutes(1).withSecond(0).withNano(0);
        java.time.ZonedDateTime nowMinuteStart = nowZdt.withSecond(0).withNano(0);

        int triggered = 0;
        for (Dag dag : candidates) {
            try {
                if (!isCronDue(dag, prevMinuteStart, nowMinuteStart)) continue;
                // Only trigger PUBLISHED pipelines (per §6.5).
                if (dag.getStatus() == null || !"PUBLISHED".equalsIgnoreCase(dag.getStatus())) {
                    continue;
                }
                // Cron scheduling belongs to unified Spark pipeline jobs.
                if (!"onelake_pipeline_run".equals(dag.getDagsterJob())) {
                    continue;
                }
                // Run under system tenant context
                TenantContext.setTenantId(dag.getTenantId());
                try {
                    orchestrationService.triggerPipelineRun(dag.getId(), TriggerType.CRON);
                    triggered++;
                    log.info("PipelineSchedulerService: triggered pipeline {} (cron={})",
                            dag.getId(), dag.getScheduleCron());
                } finally {
                    TenantContext.clear();
                }
            } catch (RuntimeException e) {
                log.warn("PipelineSchedulerService: failed to trigger pipeline {}: {}",
                        dag.getId(), e.getMessage());
            }
        }
        if (triggered > 0) {
            log.info("PipelineSchedulerService: tick triggered {} pipeline(s)", triggered);
        }
    }

    /**
     * Returns true if the pipeline's cron has a next-occurrence in the half-open interval
     * {@code (prevMinuteStart, nowMinuteStart]} — i.e. it should fire on this tick.
     */
    static boolean isCronDue(Dag dag, java.time.ZonedDateTime prevMinuteStart,
                              java.time.ZonedDateTime nowMinuteStart) {
        String cron = dag.getScheduleCron();
        if (cron == null || cron.isBlank()) return false;
        try {
            org.springframework.scheduling.support.CronExpression expr =
                    org.springframework.scheduling.support.CronExpression.parse(cron);
            // next() returns the next match strictly AFTER its argument.
            java.time.ZonedDateTime next = expr.next(prevMinuteStart);
            return next != null && !next.isAfter(nowMinuteStart);
        } catch (IllegalArgumentException e) {
            log.debug("PipelineSchedulerService: invalid cron '{}' on pipeline {}: {}",
                    cron, dag.getId(), e.getMessage());
            return false;
        }
    }
}
