package com.onelake.common.task;

import com.onelake.common.notification.NotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RunningTaskServiceTest {

    @Mock
    private RunningTaskRepository repo;

    @Mock
    @SuppressWarnings("unused")
    private NamedParameterJdbcTemplate jdbc;

    @Mock
    @SuppressWarnings("unused")
    private NotificationService notificationService;

    @InjectMocks
    private RunningTaskService service;

    @Test
    void upsertIntegrationRunCreatesCollectTaskProjection() {
        UUID tenantId = UUID.randomUUID();
        RunningTaskService.IntegrationRunProjection row = new RunningTaskService.IntegrationRunProjection(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            "orders_sync",
            "ods.orders",
            tenantId,
            "RUNNING",
            10L,
            8L,
            null,
            null,
            Instant.parse("2026-06-22T10:00:00Z"),
            null
        );
        when(repo.findByTenantIdAndRefTypeAndRefId(tenantId, "sync_run", row.runId()))
            .thenReturn(Optional.empty());

        service.upsertIntegrationRun(row);

        ArgumentCaptor<RunningTask> captor = ArgumentCaptor.forClass(RunningTask.class);
        verify(repo).saveAndFlush(captor.capture());
        RunningTask task = captor.getValue();
        assertThat(task.getTenantId()).isEqualTo(tenantId);
        assertThat(task.getSourceModule()).isEqualTo("INTEGRATION");
        assertThat(task.getTaskType()).isEqualTo("COLLECT");
        assertThat(task.getRefType()).isEqualTo("sync_run");
        assertThat(task.getRefId()).isEqualTo(row.runId());
        assertThat(task.getParentRefId()).isEqualTo(row.taskId());
        assertThat(task.getStatus()).isEqualTo("RUNNING");
        assertThat(task.getProgress()).isEqualTo(40);
        assertThat(task.getCancellable()).isTrue();
        assertThat(task.getCancelEndpoint()).isEqualTo("/integration/sync-tasks/runs/" + row.runId() + "/cancel");
        assertThat(task.getLink()).isEqualTo("/integration/sync-tasks/" + row.taskId() + "/runs/" + row.runId());
    }

    @Test
    void upsertIntegrationRunKeepsFailedTaskUntilDismissed() {
        UUID tenantId = UUID.randomUUID();
        RunningTask existing = new RunningTask();
        existing.setTenantId(tenantId);
        existing.setRefType("sync_run");
        existing.setRefId("run-1");
        RunningTaskService.IntegrationRunProjection row = new RunningTaskService.IntegrationRunProjection(
            "run-1",
            "task-1",
            "orders_sync",
            "ods.orders",
            tenantId,
            "FAILED",
            0L,
            0L,
            "AIRBYTE_JOB_FAILED",
            "source timeout",
            Instant.parse("2026-06-22T10:00:00Z"),
            Instant.parse("2026-06-22T10:01:00Z")
        );
        when(repo.findByTenantIdAndRefTypeAndRefId(tenantId, "sync_run", "run-1"))
            .thenReturn(Optional.of(existing));

        service.upsertIntegrationRun(row);

        assertThat(existing.getStatus()).isEqualTo("FAILED");
        assertThat(existing.getProgress()).isEqualTo(100);
        assertThat(existing.getCancellable()).isFalse();
        assertThat(existing.getErrorCode()).isEqualTo("AIRBYTE_JOB_FAILED");
        assertThat(existing.getErrorMessage()).isEqualTo("source timeout");
        assertThat(existing.getExpiresAt()).isNull();
        verify(repo).saveAndFlush(existing);
    }

    @Test
    void upsertSqlQueryCreatesCancelableRunningProjection() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        RunningTaskService.SqlQueryProjection row = new RunningTaskService.SqlQueryProjection(
            UUID.randomUUID().toString(),
            tenantId,
            userId,
            "dev",
            "select * from ods.orders limit 10",
            "TRINO",
            "default",
            "RUNNING",
            null,
            null,
            null,
            null,
            null,
            Instant.parse("2026-06-22T10:00:00Z")
        );
        when(repo.findByTenantIdAndRefTypeAndRefId(tenantId, "sql_query", row.queryId()))
            .thenReturn(Optional.empty());

        service.upsertSqlQuery(row);

        ArgumentCaptor<RunningTask> captor = ArgumentCaptor.forClass(RunningTask.class);
        verify(repo).saveAndFlush(captor.capture());
        RunningTask task = captor.getValue();
        assertThat(task.getSourceModule()).isEqualTo("LAKEHOUSE");
        assertThat(task.getTaskType()).isEqualTo("SQL");
        assertThat(task.getRefType()).isEqualTo("sql_query");
        assertThat(task.getStatus()).isEqualTo("RUNNING");
        assertThat(task.getUserId()).isEqualTo(userId);
        assertThat(task.getTitle()).contains("SQL 查询 select * from ods.orders");
        assertThat(task.getLink()).isEqualTo("/lakehouse/sql");
        assertThat(task.getCancellable()).isTrue();
        assertThat(task.getCancelEndpoint()).isEqualTo("/lakehouse/sql/queries/" + row.queryId() + "/cancel");
    }

    @Test
    void upsertOrchestrationRunNormalizesSuccessStatus() {
        UUID tenantId = UUID.randomUUID();
        RunningTaskService.OrchestrationRunProjection row = new RunningTaskService.OrchestrationRunProjection(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            tenantId,
            "daily_sales_pipeline",
            "onelake_pipeline_run",
            "dagster-run-1",
            "MANUAL",
            "SUCCESS",
            Instant.parse("2026-06-22T10:00:00Z"),
            Instant.parse("2026-06-22T10:02:00Z")
        );
        when(repo.findByTenantIdAndRefTypeAndRefId(tenantId, "job_run", row.runId()))
            .thenReturn(Optional.empty());

        service.upsertOrchestrationRun(row);

        ArgumentCaptor<RunningTask> captor = ArgumentCaptor.forClass(RunningTask.class);
        verify(repo).saveAndFlush(captor.capture());
        RunningTask task = captor.getValue();
        assertThat(task.getSourceModule()).isEqualTo("ORCHESTRATION");
        assertThat(task.getTaskType()).isEqualTo("DAG");
        assertThat(task.getRefType()).isEqualTo("job_run");
        assertThat(task.getParentRefId()).isEqualTo(row.dagId());
        assertThat(task.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(task.getPhase()).isEqualTo("编排完成");
        assertThat(task.getLink()).isEqualTo("/orchestration/pipelines/" + row.dagId());
        assertThat(task.getCancellable()).isFalse();
    }

    @Test
    void upsertQualityResultKeepsFailedResultUntilDismissed() {
        UUID tenantId = UUID.randomUUID();
        RunningTaskService.QualityResultProjection row = new RunningTaskService.QualityResultProjection(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            tenantId,
            "ods.orders",
            "amount",
            "RANGE",
            "BLOCK",
            false,
            new BigDecimal("96.00"),
            32L,
            Instant.parse("2026-06-22T10:00:00Z")
        );
        when(repo.findByTenantIdAndRefTypeAndRefId(tenantId, "quality_run_result", row.resultId()))
            .thenReturn(Optional.empty());

        service.upsertQualityResult(row);

        ArgumentCaptor<RunningTask> captor = ArgumentCaptor.forClass(RunningTask.class);
        verify(repo).saveAndFlush(captor.capture());
        RunningTask task = captor.getValue();
        assertThat(task.getSourceModule()).isEqualTo("QUALITY");
        assertThat(task.getTaskType()).isEqualTo("QUALITY");
        assertThat(task.getRefType()).isEqualTo("quality_run_result");
        assertThat(task.getParentRefId()).isEqualTo(row.ruleId());
        assertThat(task.getStatus()).isEqualTo("FAILED");
        assertThat(task.getProgress()).isEqualTo(100);
        assertThat(task.getErrorCode()).isEqualTo("QUALITY_CHECK_FAILED");
        assertThat(task.getErrorMessage()).contains("32 行异常");
        assertThat(task.getLink()).isEqualTo("/quality/results");
        assertThat(task.getExpiresAt()).isNull();
    }
}
