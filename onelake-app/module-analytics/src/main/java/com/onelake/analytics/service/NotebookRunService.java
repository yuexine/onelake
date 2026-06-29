package com.onelake.analytics.service;

import com.onelake.analytics.client.DagsterClient;
import com.onelake.analytics.domain.entity.Notebook;
import com.onelake.analytics.domain.entity.NotebookRun;
import com.onelake.analytics.domain.enums.RunStatus;
import com.onelake.analytics.repository.NotebookRepository;
import com.onelake.analytics.repository.NotebookRunRepository;
import com.onelake.common.audit.AuditLogger;
import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.outbox.DomainEvents;
import com.onelake.common.outbox.OutboxPublisher;
import com.onelake.common.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Notebook 调度服务（§7.8 v1.1 实现）。
 *
 * 关键：
 * 1. 调度 = 创建 NotebookRun + Dagster launchRun
 * 2. 同事务发 Outbox analytics.notebook.run-submitted
 * 3. 状态回写由 NotebookRunSyncScheduler 异步轮询 Dagster（不主动反调控制面）
 *
 * P1/P2/P3 不调用此服务；P4c 启用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotebookRunService {

    private final NotebookRepository notebookRepo;
    private final NotebookRunRepository runRepo;
    private final DagsterClient dagster;
    private final OutboxPublisher outbox;
    private final AuditLogger audit;

    @Transactional
    public NotebookRun schedule(UUID notebookId, Map<String, Object> params) {
        UUID tenant = TenantContext.getTenantId();
        Notebook nb = notebookRepo.findByIdAndTenantId(notebookId, tenant)
            .orElseThrow(() -> new BizException(40400, "Notebook 不存在"));

        NotebookRun run = new NotebookRun();
        run.setNotebookId(notebookId);
        run.setTenantId(tenant);
        run.setParams(JsonUtil.toJson(params == null ? Map.of() : params));
        run.setStatus(RunStatus.PENDING);
        runRepo.save(run);

        // 提交 Dagster onelake_notebook_run job
        String dagsterRunId = dagster.launchRun("onelake_notebook_run", Map.of(
            "notebook_path", nb.getStoragePath(),
            "parameters", params == null ? Map.of() : params,
            "run_id", run.getId().toString(),
            "tenant_id", String.valueOf(tenant)
        ));
        run.setDagsterRunId(dagsterRunId);
        run.setStatus(RunStatus.RUNNING);
        run.setStartedAt(Instant.now());

        outbox.publish(DomainEvents.ANALYTICS_NOTEBOOK_RUN_SUBMITTED, run.getId().toString(),
            Map.of("notebookId", notebookId.toString(), "runId", run.getId().toString(),
                   "dagsterRunId", dagsterRunId));
        audit.auditCreate("analytics.notebook_run", run.getId(), nb.getName());
        return run;
    }

    @Transactional(readOnly = true)
    public List<NotebookRun> listByNotebook(UUID notebookId) {
        UUID tenant = TenantContext.getTenantId();
        notebookRepo.findByIdAndTenantId(notebookId, tenant)
            .orElseThrow(() -> new BizException(40400, "Notebook 不存在"));
        return runRepo.findByNotebookIdOrderByCreatedAtDesc(notebookId);
    }

    @Transactional(readOnly = true)
    public NotebookRun get(UUID runId) {
        UUID tenant = TenantContext.getTenantId();
        return runRepo.findByIdAndTenantId(runId, tenant)
            .orElseThrow(() -> new BizException(40400, "Notebook 运行实例不存在"));
    }
}
