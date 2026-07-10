package com.onelake.orchestration.service;

import com.onelake.common.context.TenantContext;
import com.onelake.orchestration.domain.entity.Dag;
import com.onelake.orchestration.domain.entity.JobRun;
import com.onelake.orchestration.domain.enums.DagStatus;
import com.onelake.orchestration.repository.DagRepository;
import com.onelake.orchestration.repository.JobRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/** 先同步运行终态，再把 SLA/超时判定交给独立锁事务处理。 */
@Service
@RequiredArgsConstructor
public class SlaMonitorRunProcessor {

    private final JobRunRepository jobRunRepo;
    private final DagRepository dagRepo;
    private final OrchestrationService orchestrationService;
    private final SlaMonitorDecisionProcessor decisionProcessor;

    /**
     * 状态刷新和阈值判定使用两个事务，确保 Dagster 已完成的 run 在后一个事务中可见，
     * 同时避免 SLA 判定事务在远端状态查询期间占用自己的行锁。
     */
    public void process(UUID runId, Instant now) {
        JobRun run = jobRunRepo.findById(runId).orElse(null);
        if (run == null || isTerminal(run.getStatus()) || run.getStartedAt() == null) {
            return;
        }
        Dag dag = dagRepo.findById(run.getDagId()).orElse(null);
        if (dag == null || (dag.getSlaMinutes() == null && dag.getTimeoutMinutes() == null)) {
            return;
        }

        TenantContext.setTenantId(dag.getTenantId());
        try {
            // 自动刷新入口会锁定并聚合 Dagster/task_run 终态；方法返回后其事务已提交。
            orchestrationService.refreshRunStatusForAutomation(runId);
            decisionProcessor.processLocked(runId, now);
        } finally {
            TenantContext.clear();
        }
    }

    private boolean isTerminal(DagStatus status) {
        return status == DagStatus.SUCCEEDED
                || status == DagStatus.FAILED
                || status == DagStatus.CANCELLED;
    }
}
