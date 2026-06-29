package com.onelake.analytics.service;

import com.onelake.analytics.client.DagsterClient;
import com.onelake.analytics.domain.entity.NotebookRun;
import com.onelake.analytics.domain.enums.RunStatus;
import com.onelake.analytics.repository.NotebookRunRepository;
import com.onelake.common.outbox.DomainEvents;
import com.onelake.common.outbox.OutboxPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Notebook 运行状态同步调度器（§7.8 v1.1）。
 *
 * 关键设计：
 * - Dagster 端不主动反调控制面（避免双向依赖 + 鉴权穿透）
 * - 控制面侧每 30s 轮询 Dagster runStatus，更新 notebook_run.status
 * - 超 30min 仍 RUNNING 的记录兜底置 FAILED + 发 Outbox timeout 事件
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotebookRunSyncScheduler {

    private final NotebookRunRepository runRepo;
    private final DagsterClient dagster;
    private final OutboxPublisher outbox;

    @Value("${onelake.dataplane.analytics.notebook.run-timeout-minutes:30}")
    private long runTimeoutMinutes;

    /**
     * 每 30 秒扫描一次 RUNNING 状态的 notebook_run。
     */
    @Scheduled(fixedDelayString = "${onelake.dataplane.analytics.notebook.poll-interval:30000}")
    public void syncRunning() {
        List<NotebookRun> running = runRepo.findByStatusIn(List.of(RunStatus.PENDING, RunStatus.RUNNING));
        if (running.isEmpty()) return;
        log.debug("syncing {} notebook runs", running.size());

        for (NotebookRun run : running) {
            try {
                syncOne(run);
            } catch (Exception e) {
                log.warn("sync notebook_run {} failed: {}", run.getId(), e.getMessage());
            }
        }
    }

    @Transactional
    protected void syncOne(NotebookRun run) {
        if (run.getDagsterRunId() == null) return;

        // 超时兜底
        if (run.getStartedAt() != null
            && Duration.between(run.getStartedAt(), Instant.now()).toMinutes() > runTimeoutMinutes) {
            run.setStatus(RunStatus.FAILED);
            run.setFinishedAt(Instant.now());
            runRepo.save(run);
            outbox.publish(DomainEvents.ANALYTICS_NOTEBOOK_TIMEOUT, run.getId().toString(),
                Map.of("tenantId", String.valueOf(run.getTenantId()),
                       "notebookId", String.valueOf(run.getNotebookId())));
            return;
        }

        String status = dagster.runStatus(run.getDagsterRunId());
        RunStatus mapped = mapStatus(status);
        if (mapped != null && mapped != run.getStatus()) {
            run.setStatus(mapped);
            if (mapped == RunStatus.SUCCEEDED || mapped == RunStatus.FAILED
                || mapped == RunStatus.CANCELLED) {
                run.setFinishedAt(Instant.now());
            }
            runRepo.save(run);
            outbox.publish(DomainEvents.ANALYTICS_NOTEBOOK_RUN_STATUS_CHANGED, run.getId().toString(),
                Map.of("status", mapped.name()));
        }
    }

    private RunStatus mapStatus(String dagsterStatus) {
        if (dagsterStatus == null) return null;
        return switch (dagsterStatus.toUpperCase()) {
            case "QUEUED", "NOT_STARTED", "PLANNED" -> RunStatus.PENDING;
            case "STARTED", "STARTING", "CANCELING" -> RunStatus.RUNNING;
            case "SUCCESS" -> RunStatus.SUCCEEDED;
            case "FAILURE" -> RunStatus.FAILED;
            case "CANCELED" -> RunStatus.CANCELLED;
            default -> null;
        };
    }
}
