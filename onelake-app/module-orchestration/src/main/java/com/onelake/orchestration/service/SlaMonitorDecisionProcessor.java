package com.onelake.orchestration.service;

import com.onelake.common.outbox.DomainEvents;
import com.onelake.common.outbox.OutboxPublisher;
import com.onelake.orchestration.domain.entity.Dag;
import com.onelake.orchestration.domain.entity.JobRun;
import com.onelake.orchestration.domain.enums.DagStatus;
import com.onelake.orchestration.domain.enums.RunEnvironment;
import com.onelake.orchestration.repository.DagRepository;
import com.onelake.orchestration.repository.JobRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 在独立事务内锁定并处理一条已完成状态同步的 SLA/超时候选。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SlaMonitorDecisionProcessor {

    private final JobRunRepository jobRunRepo;
    private final DagRepository dagRepo;
    private final OrchestrationService orchestrationService;
    private final OutboxPublisher outboxPublisher;

    /**
     * 在行锁下重判运行状态和阈值，并让状态变更与 Outbox 事件同事务提交。
     *
     * <p>如果一条运行同时越过 SLA 和 timeout，先记录 SLA 违约，再走 M1 取消；这样
     * 两个在巡检开始时都成立的事实都会被保留。</p>
     */
    @Transactional
    public void processLocked(UUID runId, Instant now) {
        JobRun run = jobRunRepo.findByIdForUpdate(runId).orElse(null);
        if (run == null || isTerminal(run.getStatus()) || run.getStartedAt() == null) {
            return;
        }
        Dag dag = dagRepo.findById(run.getDagId()).orElse(null);
        if (dag == null || (dag.getSlaMinutes() == null && dag.getTimeoutMinutes() == null)) {
            return;
        }

        long elapsedMinutes = Math.max(0L, Duration.between(run.getStartedAt(), now).toMinutes());
        boolean devRun = RunEnvironment.DEV.name().equalsIgnoreCase(run.getRunMode());
        if (!devRun
                && !Boolean.TRUE.equals(run.getSlaMissed())
                && exceeded(run.getStartedAt(), now, dag.getSlaMinutes())) {
            run.setSlaMissed(true);
            run.setUpdatedAt(now);
            jobRunRepo.save(run);
            publish(DomainEvents.PIPELINE_RUN_SLA_MISSED, dag, run, elapsedMinutes);
        }

        if (exceeded(run.getStartedAt(), now, dag.getTimeoutMinutes())) {
            // 复用 M1：Dagster terminate 尽力而为，本地 JobRun/TaskRun 以 CANCELLED 收口。
            orchestrationService.cancelRun(runId);
            if (devRun) {
                log.info("DEV 试跑已超时取消，不发布生产 timeout 事件：pipelineId={} runId={}",
                        dag.getId(), run.getId());
            } else {
                publish(DomainEvents.PIPELINE_RUN_TIMEOUT, dag, run, elapsedMinutes);
            }
        }
    }

    private void publish(String eventType, Dag dag, JobRun run, long elapsedMinutes) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("pipelineId", dag.getId().toString());
        payload.put("runId", run.getId().toString());
        payload.put("logicalDate", run.getLogicalDate() == null ? null : run.getLogicalDate().toString());
        payload.put("slaMinutes", dag.getSlaMinutes());
        payload.put("elapsedMinutes", elapsedMinutes);
        // timeout 消费者可直接读取阈值；保留统一要求的 slaMinutes 字段以稳定事件契约。
        payload.put("timeoutMinutes", dag.getTimeoutMinutes());
        outboxPublisher.publish(eventType, dag.getId().toString(), payload);
        log.info("SLA 监控事件已写入 Outbox：eventType={} pipelineId={} runId={} elapsedMinutes={}",
                eventType, dag.getId(), run.getId(), elapsedMinutes);
    }

    private boolean exceeded(Instant startedAt, Instant now, Integer limitMinutes) {
        return limitMinutes != null && startedAt.plus(Duration.ofMinutes(limitMinutes)).isBefore(now);
    }

    private boolean isTerminal(DagStatus status) {
        return status == DagStatus.SUCCEEDED
                || status == DagStatus.FAILED
                || status == DagStatus.CANCELLED;
    }
}
