package com.onelake.orchestration.service;

import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.outbox.DomainEvents;
import com.onelake.common.util.JsonUtil;
import com.onelake.orchestration.client.DagsterClient;
import com.onelake.orchestration.domain.entity.Dag;
import com.onelake.orchestration.domain.entity.JobRun;
import com.onelake.orchestration.domain.entity.PipelineTask;
import com.onelake.orchestration.domain.entity.PipelineTaskEdge;
import com.onelake.orchestration.domain.entity.PipelineParam;
import com.onelake.orchestration.domain.entity.PipelineVersion;
import com.onelake.orchestration.domain.entity.TaskRun;
import com.onelake.orchestration.domain.enums.DagStatus;
import com.onelake.orchestration.domain.enums.EdgeLayer;
import com.onelake.orchestration.domain.enums.RunEnvironment;
import com.onelake.orchestration.domain.enums.TaskRunStatus;
import com.onelake.orchestration.domain.enums.TaskType;
import com.onelake.orchestration.domain.enums.TriggerType;
import com.onelake.orchestration.dto.DagDTO;
import com.onelake.orchestration.dto.JobRunDTO;
import com.onelake.orchestration.dto.PipelineCompileResult;
import com.onelake.orchestration.dto.TaskRunCallbackRequest;
import com.onelake.orchestration.dto.TaskRunCallbackResult;
import com.onelake.orchestration.repository.DagRepository;
import com.onelake.orchestration.repository.JobRunRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

// 统一 Spark 运行路径依赖。
import com.onelake.common.outbox.OutboxPublisher;
import com.onelake.orchestration.repository.PipelineTaskEdgeRepository;
import com.onelake.orchestration.repository.PipelineTaskRepository;
import com.onelake.orchestration.repository.TaskRunRepository;
import com.onelake.orchestration.service.spi.DagsterRunConfig;
import com.onelake.orchestration.service.spi.SparkRunConfigBuilder;

import java.sql.Timestamp;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class OrchestrationServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DAG_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID RUN_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String GRAPH_JOB = SparkRunConfigBuilder.graphJobName(DAG_ID);
    @Mock
    private DagRepository dagRepo;

    @Mock
    private JobRunRepository runRepo;

    @Mock
    private DagsterClient dagster;

    @Mock
    private JdbcTemplate jdbc;

    @Mock
    private RuntimeContractService runtimeContractService;

    // 编排运行测试共享的流水线依赖。
    @Mock private PipelineCompileService pipelineCompileService;
    @Mock private PipelineSnapshotService pipelineSnapshotService;
    @Mock private PipelineTaskRepository pipelineTaskRepo;
    @Mock private PipelineTaskEdgeRepository pipelineTaskEdgeRepo;
    @Mock private TaskRunRepository taskRunRepo;
    @Mock private SparkRunConfigBuilder sparkBuilder;
    @Mock private ObjectProvider<OutboxPublisher> outboxPublisher;
    @Mock private OutboxPublisher publisher;
    @Mock private PipelineLogStorage pipelineLogStorage;
    @Mock private ScriptSandboxPolicy scriptSandboxPolicy;

    private OrchestrationService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT_ID);
        lenient().when(runtimeContractService.triggerBlockedReason(anyString(), anyMap())).thenReturn(Optional.empty());
        lenient().when(runtimeContractService.launchBlockedReason(anyString(), anyMap())).thenReturn(Optional.empty());
        lenient().when(runRepo.findByIdForUpdate(any())).thenReturn(Optional.empty());
        lenient().when(pipelineSnapshotService.versionNumbers(any())).thenReturn(Map.of());
        service = new OrchestrationService(dagRepo, runRepo, dagster, jdbc,
            runtimeContractService, pipelineCompileService, pipelineSnapshotService,
            pipelineTaskRepo, pipelineTaskEdgeRepo, taskRunRepo,
            sparkBuilder, outboxPublisher, pipelineLogStorage, new DataIntervalCalculator(),
            scriptSandboxPolicy);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void updateDagPersistsDefinitionAndIncrementsVersion() {
        Dag dag = dag();
        when(dagRepo.findByIdAndTenantId(DAG_ID, TENANT_ID)).thenReturn(Optional.of(dag));
        Map<String, Object> definition = Map.of(
            "operatorGraph", Map.of(
                "nodes", List.of(Map.of("id", "input_ods", "operatorRef", "input.ods_table")),
                "edges", List.of()
            )
        );

        DagDTO dto = service.updateDag(DAG_ID, "trade_dwd_pipeline", "onelake_pipeline_run",
            definition, "0 2 * * *", false);

        assertThat(dto.name()).isEqualTo("trade_dwd_pipeline");
        assertThat(dto.version()).isEqualTo(3);
        assertThat(dto.enabled()).isFalse();
        assertThat(dto.definition()).containsKey("operatorGraph");
        assertThat(dag.getDefinition()).contains("input.ods_table");
        verify(dagRepo).save(dag);
    }

    @Test
    void updateDagRejectsDagOutsideTenant() {
        when(dagRepo.findByIdAndTenantId(DAG_ID, TENANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateDag(DAG_ID, "pipeline", "job", Map.of("nodes", List.of()), null, null))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("DAG 不存在");
    }

    @Test
    void listRunsScopesToTenantDagsAndIncludesDagMetadata() {
        Dag dag = dag();
        JobRun run = jobRun(dag.getId());
        run.setTimezone("UTC");
        run.setRunMode("DRY_RUN");
        run.setSlaMissed(true);
        run.setRetrySourceRunId(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
        run.setRunRetryAttempt(2);
        UUID pipelineVersionId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        run.setPipelineVersionId(pipelineVersionId);
        PageRequest pageable = PageRequest.of(0, 20);
        when(dagRepo.findByTenantId(TENANT_ID)).thenReturn(List.of(dag));
        when(runRepo.findByDagIdInOrderByStartedAtDesc(
            argThat(ids -> ids != null && ids.size() == 1 && ids.contains(DAG_ID)),
            eq(pageable)
        )).thenReturn(new PageImpl<>(List.of(run), pageable, 1));
        when(pipelineSnapshotService.versionNumbers(Set.of(pipelineVersionId)))
                .thenReturn(Map.of(pipelineVersionId, 4));

        Page<JobRunDTO> page = service.listRuns(pageable);

        assertThat(page.getTotalElements()).isEqualTo(1);
        JobRunDTO dto = page.getContent().get(0);
        assertThat(dto.dagId()).isEqualTo(DAG_ID);
        assertThat(dto.dagName()).isEqualTo("old_pipeline");
        assertThat(dto.dagsterJob()).isEqualTo("old_job");
        assertThat(dto.dagsterRunId()).isEqualTo("dagster-run-1");
        assertThat(dto.status()).isEqualTo("SUCCEEDED");
        assertThat(dto.triggerType()).isEqualTo("MANUAL");
        assertThat(dto.timezone()).isEqualTo("UTC");
        assertThat(dto.runMode()).isEqualTo("DRY_RUN");
        assertThat(dto.slaMissed()).isTrue();
        assertThat(dto.retrySourceRunId()).isEqualTo(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
        assertThat(dto.runRetryAttempt()).isEqualTo(2);
        assertThat(dto.pipelineVersionId()).isEqualTo(pipelineVersionId);
        assertThat(dto.pipelineVersion()).isEqualTo(4);
    }

    @Test
    void listBackfillRunsScopesToTenantDagAndPreservesJobMetadata() {
        UUID backfillId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        Dag dag = dag();
        JobRun run = jobRun(dag.getId());
        run.setDagsterRunId(null);
        run.setTriggerType(TriggerType.BACKFILL);
        run.setBackfillId(backfillId);
        run.setLogicalDate(Instant.parse("2026-07-01T00:00:00Z"));
        PageRequest pageable = PageRequest.of(0, 20);
        when(dagRepo.findByIdAndTenantId(DAG_ID, TENANT_ID)).thenReturn(Optional.of(dag));
        when(runRepo.findByDagIdAndBackfillIdOrderByLogicalDateAsc(DAG_ID, backfillId, pageable))
                .thenReturn(new PageImpl<>(List.of(run), pageable, 1));

        Page<JobRunDTO> page = service.listBackfillRuns(DAG_ID, backfillId, pageable);

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).backfillId()).isEqualTo(backfillId);
        assertThat(page.getContent().get(0).logicalDate()).isEqualTo(Instant.parse("2026-07-01T00:00:00Z"));
        verify(runRepo).findByDagIdAndBackfillIdOrderByLogicalDateAsc(DAG_ID, backfillId, pageable);
    }

    @Test
    void getBackfillRunRequiresMatchingTenantDagAndBackfill() {
        UUID backfillId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        Dag dag = dag();
        JobRun run = jobRun(dag.getId());
        run.setDagsterRunId(null);
        run.setTriggerType(TriggerType.BACKFILL);
        run.setBackfillId(backfillId);
        when(dagRepo.findByIdAndTenantId(DAG_ID, TENANT_ID)).thenReturn(Optional.of(dag));
        when(runRepo.findByIdAndDagIdAndBackfillId(RUN_ID, DAG_ID, backfillId)).thenReturn(Optional.of(run));

        JobRunDTO result = service.getBackfillRun(DAG_ID, backfillId, RUN_ID);

        assertThat(result.id()).isEqualTo(RUN_ID);
        assertThat(result.backfillId()).isEqualTo(backfillId);
        verify(runRepo).findByIdAndDagIdAndBackfillId(RUN_ID, DAG_ID, backfillId);
    }

    @Test
    void listRunsRefreshesNonTerminalDagsterStatus() {
        Dag dag = dag();
        JobRun run = jobRun(dag.getId());
        run.setStatus(DagStatus.RUNNING);
        run.setFinishedAt(null);
        Instant startedAt = Instant.parse("2026-06-23T02:00:00Z");
        Instant finishedAt = Instant.parse("2026-06-23T02:03:00Z");
        PageRequest pageable = PageRequest.of(0, 20);
        when(dagRepo.findByTenantId(TENANT_ID)).thenReturn(List.of(dag));
        when(runRepo.findByDagIdInOrderByStartedAtDesc(
            argThat(ids -> ids != null && ids.contains(DAG_ID)),
            eq(pageable)
        )).thenReturn(new PageImpl<>(List.of(run), pageable, 1));
        when(dagster.getRunStatus("dagster-run-1"))
            .thenReturn(new DagsterClient.RunStatus("dagster-run-1", "SUCCEEDED", startedAt, finishedAt));

        Page<JobRunDTO> page = service.listRuns(pageable);

        JobRunDTO dto = page.getContent().get(0);
        assertThat(dto.status()).isEqualTo("SUCCEEDED");
        assertThat(dto.startedAt()).isEqualTo(startedAt);
        assertThat(dto.finishedAt()).isEqualTo(finishedAt);
        verify(runRepo).save(run);
        // 运行状态刷新不得回写建模模块持有的表。
        verify(jdbc, never()).update(startsWith("UPDATE modeling.model_run"), any(), any(), any(), any());
    }

    @Test
    void graphRefreshKeepsJobRunFailedWhenSucceededSubgraphLeavesBlockedTasks() {
        ReflectionTestUtils.setField(service, "pipelineExecutionMode", "GRAPH");
        Dag dag = dag();
        JobRun run = jobRun(dag.getId());
        run.setStatus(DagStatus.RUNNING);
        run.setFinishedAt(null);
        TaskRun upstream = taskRun("sync_ref", TaskRunStatus.SUCCEEDED);
        TaskRun rerunNode = taskRun("spark_sql", TaskRunStatus.SUCCEEDED);
        TaskRun blocked = taskRun("quality_gate", TaskRunStatus.UPSTREAM_FAILED);
        blocked.setErrorMsg("Upstream task failed: spark_sql");
        Instant startedAt = Instant.parse("2026-07-09T02:00:00Z");
        Instant finishedAt = Instant.parse("2026-07-09T02:03:00Z");
        PageRequest pageable = PageRequest.of(0, 20);
        when(dagRepo.findByTenantId(TENANT_ID)).thenReturn(List.of(dag));
        when(runRepo.findByDagIdInOrderByStartedAtDesc(
                argThat(ids -> ids != null && ids.contains(DAG_ID)),
                eq(pageable)
        )).thenReturn(new PageImpl<>(List.of(run), pageable, 1));
        when(dagster.getRunStatus("dagster-run-1"))
                .thenReturn(new DagsterClient.RunStatus("dagster-run-1", "SUCCEEDED", startedAt, finishedAt));
        when(taskRunRepo.findByJobRunIdForUpdate(RUN_ID)).thenReturn(List.of(upstream, rerunNode, blocked));

        Page<JobRunDTO> page = service.listRuns(pageable);

        assertThat(page.getContent().get(0).status()).isEqualTo("FAILED");
        assertThat(run.getStatus()).isEqualTo(DagStatus.FAILED);
        assertThat(run.getFinishedAt()).isEqualTo(finishedAt);
        assertThat(blocked.getStatus()).isEqualTo(TaskRunStatus.UPSTREAM_FAILED);
        verify(runRepo, times(2)).save(run);
        verify(taskRunRepo, never()).save(blocked);
    }

    @Test
    void graphRefreshTreatsIntentionalSkippedTasksAsSuccessfulRun() {
        ReflectionTestUtils.setField(service, "pipelineExecutionMode", "GRAPH");
        Dag dag = dag();
        JobRun run = jobRun(dag.getId());
        run.setStatus(DagStatus.RUNNING);
        TaskRun condition = taskRun("condition", TaskRunStatus.SUCCEEDED);
        TaskRun selected = taskRun("selected", TaskRunStatus.SUCCEEDED);
        TaskRun skipped = taskRun("not_selected", TaskRunStatus.SKIPPED);
        Instant startedAt = Instant.parse("2026-07-12T02:00:00Z");
        Instant finishedAt = Instant.parse("2026-07-12T02:03:00Z");
        PageRequest pageable = PageRequest.of(0, 20);
        when(dagRepo.findByTenantId(TENANT_ID)).thenReturn(List.of(dag));
        when(runRepo.findByDagIdInOrderByStartedAtDesc(
                argThat(ids -> ids != null && ids.contains(DAG_ID)),
                eq(pageable)
        )).thenReturn(new PageImpl<>(List.of(run), pageable, 1));
        when(dagster.getRunStatus("dagster-run-1"))
                .thenReturn(new DagsterClient.RunStatus(
                        "dagster-run-1", "SUCCEEDED", startedAt, finishedAt));
        when(taskRunRepo.findByJobRunIdForUpdate(RUN_ID))
                .thenReturn(List.of(condition, selected, skipped));

        Page<JobRunDTO> page = service.listRuns(pageable);

        assertThat(page.getContent().get(0).status()).isEqualTo("SUCCEEDED");
        assertThat(run.getStatus()).isEqualTo(DagStatus.SUCCEEDED);
        assertThat(skipped.getStatus()).isEqualTo(TaskRunStatus.SKIPPED);
        verify(taskRunRepo, never()).save(any(TaskRun.class));
    }

    @Test
    void graphRefreshReconcilesFinishedRunWhenDagsterStillReportsStarted() {
        ReflectionTestUtils.setField(service, "pipelineExecutionMode", "GRAPH");
        Dag dag = dag();
        JobRun run = jobRun(dag.getId());
        run.setStatus(DagStatus.RUNNING);
        Instant startedAt = Instant.parse("2026-07-09T02:00:00Z");
        Instant finishedAt = Instant.parse("2026-07-09T02:03:00Z");
        run.setStartedAt(startedAt);
        run.setFinishedAt(finishedAt);
        TaskRun done = taskRun("fast_done", TaskRunStatus.SUCCEEDED);
        TaskRun left = taskRun("slow_left", TaskRunStatus.CANCELLED);
        TaskRun right = taskRun("slow_right", TaskRunStatus.CANCELLED);
        PageRequest pageable = PageRequest.of(0, 20);
        when(dagRepo.findByTenantId(TENANT_ID)).thenReturn(List.of(dag));
        when(runRepo.findByDagIdInOrderByStartedAtDesc(
                argThat(ids -> ids != null && ids.contains(DAG_ID)),
                eq(pageable)
        )).thenReturn(new PageImpl<>(List.of(run), pageable, 1));
        when(dagster.getRunStatus("dagster-run-1"))
                .thenReturn(new DagsterClient.RunStatus("dagster-run-1", "STARTED", startedAt, null));
        when(taskRunRepo.findByJobRunIdForUpdate(RUN_ID)).thenReturn(List.of(done, left, right));

        Page<JobRunDTO> page = service.listRuns(pageable);

        assertThat(page.getContent().get(0).status()).isEqualTo("CANCELLED");
        assertThat(run.getStatus()).isEqualTo(DagStatus.CANCELLED);
        assertThat(run.getFinishedAt()).isEqualTo(finishedAt);
        verify(runRepo).save(run);
        verify(taskRunRepo, times(2)).findByJobRunIdForUpdate(RUN_ID);
        verify(taskRunRepo, never()).save(any(TaskRun.class));
    }

    @Test
    void listRunsKeepsTerminalRunLocalWithoutDagsterRefresh() {
        Dag dag = dag();
        JobRun run = jobRun(dag.getId());
        run.setStatus(DagStatus.SUCCEEDED);
        Instant startedAt = Instant.parse("2026-06-23T02:00:00Z");
        Instant finishedAt = Instant.parse("2026-06-23T02:03:00Z");
        run.setStartedAt(startedAt);
        run.setFinishedAt(finishedAt);
        PageRequest pageable = PageRequest.of(0, 20);
        when(dagRepo.findByTenantId(TENANT_ID)).thenReturn(List.of(dag));
        when(runRepo.findByDagIdInOrderByStartedAtDesc(
            argThat(ids -> ids != null && ids.contains(DAG_ID)),
            eq(pageable)
        )).thenReturn(new PageImpl<>(List.of(run), pageable, 1));

        Page<JobRunDTO> page = service.listRuns(pageable);

        assertThat(page.getContent().get(0).status()).isEqualTo("SUCCEEDED");
        verify(dagster, never()).getRunStatus("dagster-run-1");
        // 运行状态读取应只停留在编排模块内。
        verify(jdbc, never()).update(startsWith("UPDATE modeling.model_run"), any(), any(), any(), any());
    }

    @Test
    void graphTerminalRunReconcilesPersistedSuccessWhenTasksRemainBlocked() {
        ReflectionTestUtils.setField(service, "pipelineExecutionMode", "GRAPH");
        Dag dag = dag();
        JobRun run = jobRun(dag.getId());
        run.setStatus(DagStatus.SUCCEEDED);
        TaskRun upstream = taskRun("sync_ref", TaskRunStatus.SUCCEEDED);
        TaskRun rerunNode = taskRun("spark_sql", TaskRunStatus.SUCCEEDED);
        TaskRun blocked = taskRun("quality_gate", TaskRunStatus.UPSTREAM_FAILED);
        PageRequest pageable = PageRequest.of(0, 20);
        when(dagRepo.findByTenantId(TENANT_ID)).thenReturn(List.of(dag));
        when(runRepo.findByDagIdInOrderByStartedAtDesc(
                argThat(ids -> ids != null && ids.contains(DAG_ID)),
                eq(pageable)
        )).thenReturn(new PageImpl<>(List.of(run), pageable, 1));
        when(taskRunRepo.findByJobRunIdForUpdate(RUN_ID)).thenReturn(List.of(upstream, rerunNode, blocked));

        Page<JobRunDTO> page = service.listRuns(pageable);

        assertThat(page.getContent().get(0).status()).isEqualTo("FAILED");
        assertThat(run.getStatus()).isEqualTo(DagStatus.FAILED);
        verify(runRepo).save(run);
        verify(dagster, never()).getRunStatus("dagster-run-1");
    }

    @Test
    void listRunsDoesNotWriteModelingRunTables() {
        Dag dag = dag();
        JobRun run = jobRun(dag.getId());
        run.setStatus(DagStatus.SUCCEEDED);
        run.setFinishedAt(Instant.parse("2026-06-23T02:03:00Z"));
        PageRequest pageable = PageRequest.of(0, 20);
        when(dagRepo.findByTenantId(TENANT_ID)).thenReturn(List.of(dag));
        when(runRepo.findByDagIdInOrderByStartedAtDesc(
            argThat(ids -> ids != null && ids.contains(DAG_ID)),
            eq(pageable)
        )).thenReturn(new PageImpl<>(List.of(run), pageable, 1));

        service.listRuns(pageable);

        verify(jdbc, never()).update(startsWith("UPDATE modeling.model_run"), any(), any(), any(), any());
    }

    @Test
    void listRunsReturnsEmptyPageWhenTenantHasNoDags() {
        PageRequest pageable = PageRequest.of(0, 20);
        when(dagRepo.findByTenantId(TENANT_ID)).thenReturn(List.of());

        Page<JobRunDTO> page = service.listRuns(pageable);

        assertThat(page.getContent()).isEmpty();
        verifyNoInteractions(runRepo);
    }

    @Test
    void cancelRunTerminatesDagsterAndMarksNonTerminalTaskRunsCancelled() {
        Dag dag = dag();
        JobRun run = jobRun(DAG_ID);
        run.setStatus(DagStatus.RUNNING);
        run.setFinishedAt(null);
        TaskRun succeeded = taskRun("extract", TaskRunStatus.SUCCEEDED);
        Instant succeededFinishedAt = Instant.parse("2026-07-09T01:01:00Z");
        succeeded.setFinishedAt(succeededFinishedAt);
        TaskRun running = taskRun("transform", TaskRunStatus.RUNNING);
        TaskRun queued = taskRun("load", TaskRunStatus.QUEUED);
        when(dagRepo.findByTenantId(TENANT_ID)).thenReturn(List.of(dag));
        when(runRepo.findByIdAndDagIdInForUpdate(eq(RUN_ID), eq(Set.of(DAG_ID)))).thenReturn(Optional.of(run));
        when(dagster.terminate("dagster-run-1", false)).thenReturn(true);
        when(taskRunRepo.findByJobRunIdForUpdate(RUN_ID)).thenReturn(List.of(succeeded, running, queued));
        when(outboxPublisher.getIfAvailable()).thenReturn(publisher);

        JobRunDTO dto = service.cancelRun(RUN_ID);

        assertThat(dto.status()).isEqualTo("CANCELLED");
        assertThat(run.getStatus()).isEqualTo(DagStatus.CANCELLED);
        assertThat(run.getFinishedAt()).isNotNull();
        assertThat(succeeded.getStatus()).isEqualTo(TaskRunStatus.SUCCEEDED);
        assertThat(succeeded.getFinishedAt()).isEqualTo(succeededFinishedAt);
        assertThat(running.getStatus()).isEqualTo(TaskRunStatus.CANCELLED);
        assertThat(running.getFinishedAt()).isEqualTo(run.getFinishedAt());
        assertThat(queued.getStatus()).isEqualTo(TaskRunStatus.CANCELLED);
        assertThat(queued.getFinishedAt()).isEqualTo(run.getFinishedAt());
        verify(dagster).terminate("dagster-run-1", false);
        verify(runRepo).save(run);
        verify(taskRunRepo, never()).save(succeeded);
        verify(taskRunRepo).save(running);
        verify(taskRunRepo).save(queued);
        verify(publisher).publish(eq(DomainEvents.PIPELINE_RUN_FAILED), eq(DAG_ID.toString()),
                org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(payload ->
                        RUN_ID.toString().equals(payload.get("runId"))
                                && "cancelled".equals(payload.get("errorMsg"))));
    }

    @Test
    void cancelRunIsIdempotentForTerminalRun() {
        Dag dag = dag();
        JobRun run = jobRun(DAG_ID);
        run.setStatus(DagStatus.CANCELLED);
        when(dagRepo.findByTenantId(TENANT_ID)).thenReturn(List.of(dag));
        when(runRepo.findByIdAndDagIdInForUpdate(eq(RUN_ID), eq(Set.of(DAG_ID)))).thenReturn(Optional.of(run));

        JobRunDTO dto = service.cancelRun(RUN_ID);

        assertThat(dto.status()).isEqualTo("CANCELLED");
        verify(dagster, never()).terminate(anyString(), anyBoolean());
        verify(taskRunRepo, never()).findByJobRunIdForUpdate(any());
        verify(runRepo, never()).save(any(JobRun.class));
        verify(outboxPublisher, never()).getIfAvailable();
    }

    @Test
    void cancelRunWithoutDagsterRunIdMarksLocalStateOnly() {
        Dag dag = dag();
        JobRun run = jobRun(DAG_ID);
        run.setDagsterRunId(null);
        run.setStatus(DagStatus.RUNNING);
        run.setFinishedAt(null);
        TaskRun running = taskRun("transform", TaskRunStatus.RUNNING);
        when(dagRepo.findByTenantId(TENANT_ID)).thenReturn(List.of(dag));
        when(runRepo.findByIdAndDagIdInForUpdate(eq(RUN_ID), eq(Set.of(DAG_ID)))).thenReturn(Optional.of(run));
        when(taskRunRepo.findByJobRunIdForUpdate(RUN_ID)).thenReturn(List.of(running));

        JobRunDTO dto = service.cancelRun(RUN_ID);

        assertThat(dto.status()).isEqualTo("CANCELLED");
        assertThat(run.getStatus()).isEqualTo(DagStatus.CANCELLED);
        assertThat(running.getStatus()).isEqualTo(TaskRunStatus.CANCELLED);
        verify(dagster, never()).terminate(anyString(), anyBoolean());
        verify(runRepo).save(run);
        verify(taskRunRepo).save(running);
    }

    @Test
    void cancelRunContinuesWhenDagsterTerminateFails() {
        Dag dag = dag();
        JobRun run = jobRun(DAG_ID);
        run.setStatus(DagStatus.RUNNING);
        run.setFinishedAt(null);
        TaskRun running = taskRun("transform", TaskRunStatus.RUNNING);
        when(dagRepo.findByTenantId(TENANT_ID)).thenReturn(List.of(dag));
        when(runRepo.findByIdAndDagIdInForUpdate(eq(RUN_ID), eq(Set.of(DAG_ID)))).thenReturn(Optional.of(run));
        when(dagster.terminate("dagster-run-1", false)).thenThrow(new RuntimeException("dagster down"));
        when(taskRunRepo.findByJobRunIdForUpdate(RUN_ID)).thenReturn(List.of(running));

        JobRunDTO dto = service.cancelRun(RUN_ID);

        assertThat(dto.status()).isEqualTo("CANCELLED");
        assertThat(run.getStatus()).isEqualTo(DagStatus.CANCELLED);
        assertThat(running.getStatus()).isEqualTo(TaskRunStatus.CANCELLED);
        verify(runRepo).save(run);
        verify(taskRunRepo).save(running);
    }

    @Test
    void cancelRunRejectsRunOutsideTenant() {
        Dag dag = dag();
        when(dagRepo.findByTenantId(TENANT_ID)).thenReturn(List.of(dag));
        when(runRepo.findByIdAndDagIdInForUpdate(eq(RUN_ID), eq(Set.of(DAG_ID)))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancelRun(RUN_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("运行实例不存在");

        verify(dagster, never()).terminate(anyString(), anyBoolean());
        verify(taskRunRepo, never()).findByJobRunIdForUpdate(any());
    }

    @Test
    void listRunsMapsDagsterCanceledToCancelledAndCompensatesPendingTaskRuns() {
        Dag dag = dag();
        JobRun run = jobRun(dag.getId());
        run.setStatus(DagStatus.RUNNING);
        run.setFinishedAt(null);
        TaskRun pending = taskRun("spark_node", TaskRunStatus.RUNNING);
        Instant finishedAt = Instant.parse("2026-07-09T02:03:00Z");
        PageRequest pageable = PageRequest.of(0, 20);
        when(dagRepo.findByTenantId(TENANT_ID)).thenReturn(List.of(dag));
        when(runRepo.findByDagIdInOrderByStartedAtDesc(
                argThat(ids -> ids != null && ids.contains(DAG_ID)),
                eq(pageable)
        )).thenReturn(new PageImpl<>(List.of(run), pageable, 1));
        when(dagster.getRunStatus("dagster-run-1"))
                .thenReturn(new DagsterClient.RunStatus("dagster-run-1", "CANCELED", null, finishedAt));
        when(taskRunRepo.findByJobRunId(RUN_ID)).thenReturn(List.of(pending));

        Page<JobRunDTO> page = service.listRuns(pageable);

        assertThat(page.getContent().get(0).status()).isEqualTo("CANCELLED");
        assertThat(run.getStatus()).isEqualTo(DagStatus.CANCELLED);
        assertThat(run.getFinishedAt()).isEqualTo(finishedAt);
        assertThat(pending.getStatus()).isEqualTo(TaskRunStatus.CANCELLED);
        assertThat(pending.getFinishedAt()).isEqualTo(finishedAt);
        assertThat(pending.getErrorMsg()).isNull();
        verify(pipelineTaskEdgeRepo, never()).findByDagId(any());
        verify(taskRunRepo).save(pending);
    }

    @Test
    void terminalCancelledRunIsTerminalAndCompensatesWithoutDagsterRefresh() {
        Dag dag = dag();
        JobRun run = jobRun(dag.getId());
        run.setStatus(DagStatus.CANCELLED);
        TaskRun pending = taskRun("spark_node", TaskRunStatus.QUEUED);
        PageRequest pageable = PageRequest.of(0, 20);
        when(dagRepo.findByTenantId(TENANT_ID)).thenReturn(List.of(dag));
        when(runRepo.findByDagIdInOrderByStartedAtDesc(
                argThat(ids -> ids != null && ids.contains(DAG_ID)),
                eq(pageable)
        )).thenReturn(new PageImpl<>(List.of(run), pageable, 1));
        when(taskRunRepo.findByJobRunId(RUN_ID)).thenReturn(List.of(pending));

        Page<JobRunDTO> page = service.listRuns(pageable);

        assertThat(page.getContent().get(0).status()).isEqualTo("CANCELLED");
        assertThat(pending.getStatus()).isEqualTo(TaskRunStatus.CANCELLED);
        assertThat(pending.getFinishedAt()).isEqualTo(run.getFinishedAt());
        verify(dagster, never()).getRunStatus("dagster-run-1");
        verify(taskRunRepo).save(pending);
    }

    @Test
    void refreshUsesLockedCancelledRunAndDoesNotOverwriteWithStaleRunningEntity() {
        Dag dag = dag();
        JobRun staleRun = jobRun(dag.getId());
        staleRun.setStatus(DagStatus.RUNNING);
        staleRun.setFinishedAt(null);
        JobRun lockedCancelled = jobRun(dag.getId());
        lockedCancelled.setStatus(DagStatus.CANCELLED);
        PageRequest pageable = PageRequest.of(0, 20);
        when(dagRepo.findByTenantId(TENANT_ID)).thenReturn(List.of(dag));
        when(runRepo.findByDagIdInOrderByStartedAtDesc(
                argThat(ids -> ids != null && ids.contains(DAG_ID)),
                eq(pageable)
        )).thenReturn(new PageImpl<>(List.of(staleRun), pageable, 1));
        when(runRepo.findByIdForUpdate(RUN_ID)).thenReturn(Optional.of(lockedCancelled));
        when(taskRunRepo.findByJobRunId(RUN_ID)).thenReturn(List.of());

        Page<JobRunDTO> page = service.listRuns(pageable);

        assertThat(page.getContent().get(0).status()).isEqualTo("CANCELLED");
        verify(dagster, never()).getRunStatus("dagster-run-1");
        verify(runRepo, never()).save(staleRun);
    }

    @Test
    void graphTerminalRunCompensatesCancelledWithoutUpstreamFailurePropagation() {
        ReflectionTestUtils.setField(service, "pipelineExecutionMode", "GRAPH");
        Dag dag = dag();
        JobRun run = jobRun(dag.getId());
        run.setStatus(DagStatus.RUNNING);
        run.setFinishedAt(null);
        TaskRun callbackTerminal = taskRun("extract", TaskRunStatus.SUCCEEDED);
        TaskRun failedTerminal = taskRun("transform", TaskRunStatus.FAILED);
        failedTerminal.setErrorMsg("boom");
        TaskRun pending = taskRun("load", TaskRunStatus.QUEUED);
        Instant finishedAt = Instant.parse("2026-07-09T02:03:00Z");
        PageRequest pageable = PageRequest.of(0, 20);
        when(dagRepo.findByTenantId(TENANT_ID)).thenReturn(List.of(dag));
        when(runRepo.findByDagIdInOrderByStartedAtDesc(
                argThat(ids -> ids != null && ids.contains(DAG_ID)),
                eq(pageable)
        )).thenReturn(new PageImpl<>(List.of(run), pageable, 1));
        when(dagster.getRunStatus("dagster-run-1"))
                .thenReturn(new DagsterClient.RunStatus("dagster-run-1", "CANCELLED", null, finishedAt));
        when(taskRunRepo.findByJobRunIdForUpdate(RUN_ID)).thenReturn(List.of(callbackTerminal, failedTerminal, pending));

        Page<JobRunDTO> page = service.listRuns(pageable);

        assertThat(page.getContent().get(0).status()).isEqualTo("CANCELLED");
        assertThat(callbackTerminal.getStatus()).isEqualTo(TaskRunStatus.SUCCEEDED);
        assertThat(failedTerminal.getStatus()).isEqualTo(TaskRunStatus.FAILED);
        assertThat(pending.getStatus()).isEqualTo(TaskRunStatus.CANCELLED);
        assertThat(pending.getFinishedAt()).isEqualTo(finishedAt);
        assertThat(pending.getErrorMsg()).isNull();
        verify(pipelineTaskEdgeRepo, never()).findByDagId(any());
        verify(taskRunRepo, never()).save(callbackTerminal);
        verify(taskRunRepo, never()).save(failedTerminal);
        verify(taskRunRepo).save(pending);
    }

    @Test
    void listDagsIncludesLatestProdRunMetadata() {
        Dag dag = dag();
        UUID publishedVersionId = UUID.randomUUID();
        dag.setPublishedVersionId(publishedVersionId);
        when(dagRepo.findByTenantId(TENANT_ID)).thenReturn(List.of(dag));
        when(runRepo.findFirstByDagIdAndRunModeNotOrderByStartedAtDesc(DAG_ID, "DEV"))
                .thenReturn(Optional.of(jobRun(DAG_ID)));

        List<DagDTO> dags = service.listDags();

        assertThat(dags).hasSize(1);
        assertThat(dags.get(0).lastRun()).isNotNull();
        assertThat(dags.get(0).lastRun().dagsterRunId()).isEqualTo("dagster-run-1");
        assertThat(dags.get(0).lastRun().status()).isEqualTo("SUCCEEDED");
        assertThat(dags.get(0).lastRun().dagName()).isEqualTo("old_pipeline");
        assertThat(dags.get(0).triggerable()).isTrue();
        assertThat(dags.get(0).triggerBlockedReason()).isNull();
        assertThat(dags.get(0).publishedVersionId()).isEqualTo(publishedVersionId);
        verify(runRepo).findFirstByDagIdAndRunModeNotOrderByStartedAtDesc(DAG_ID, "DEV");
    }

    @Test
    void triggerDagPersistsQueuedThenRunningRun() {
        Dag dag = dag();
        List<DagStatus> statuses = captureRunStatuses();
        when(dagRepo.findByIdAndTenantId(DAG_ID, TENANT_ID)).thenReturn(Optional.of(dag));
        when(dagster.launch("old_job", "onelake", "onelake-loc")).thenReturn("dagster-run-ok");

        UUID runId = service.triggerDag(DAG_ID, TriggerType.MANUAL);

        assertThat(runId).isEqualTo(RUN_ID);
        assertThat(statuses).containsExactly(DagStatus.QUEUED, DagStatus.RUNNING);
        verify(runRepo, times(2)).save(any(JobRun.class));
    }

    @Test
    void triggerDagKeepsFailedRunWhenDagsterLaunchFails() {
        Dag dag = dag();
        List<DagStatus> statuses = captureRunStatuses();
        when(dagRepo.findByIdAndTenantId(DAG_ID, TENANT_ID)).thenReturn(Optional.of(dag));
        when(dagster.launch("old_job", "onelake", "onelake-loc"))
            .thenThrow(new RuntimeException("connection refused"));

        assertThatThrownBy(() -> service.triggerDag(DAG_ID, TriggerType.MANUAL))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("Dagster 触发失败");
        assertThat(statuses).containsExactly(DagStatus.QUEUED, DagStatus.FAILED);
        verify(runRepo, times(2)).save(any(JobRun.class));
    }

    @Test
    void triggerNonUnifiedRuntimeJobIsBlockedByRuntimeContract() {
        Dag dag = dag();
        dag.setDagsterJob("external_model_run");
        dag.setDefinition("""
            {
              "kind": "EXTERNAL_MODEL_DAG",
              "modelId": "44444444-4444-4444-4444-444444444444",
              "modelName": "dwd_orders",
              "sourceFqn": "ods.orders",
              "targetFqn": "dwd.orders",
              "artifactPath": "models/intermediate/dwd_orders.sql",
              "resourceGroup": "spark-default",
              "computeProfile": "spark-small"
            }
            """);
        when(dagRepo.findByIdAndTenantId(DAG_ID, TENANT_ID)).thenReturn(Optional.of(dag));
        when(runtimeContractService.triggerBlockedReason(eq("external_model_run"), anyMap()))
            .thenReturn(Optional.of("仅支持统一 Spark 流水线运行"));

        assertThatThrownBy(() -> service.triggerDag(DAG_ID, TriggerType.MANUAL))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("仅支持统一 Spark 流水线运行");

        verifyNoInteractions(dagster);
    }

    @Test
    void triggerDagRejectsDraftDagsterJobBeforeCreatingRun() {
        Dag dag = dag();
        dag.setDagsterJob("sql_workbench_draft");
        when(dagRepo.findByIdAndTenantId(DAG_ID, TENANT_ID)).thenReturn(Optional.of(dag));

        assertThatThrownBy(() -> service.triggerDag(DAG_ID, TriggerType.MANUAL))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("尚未绑定可执行 Dagster 作业");

        verifyNoInteractions(runRepo);
        verifyNoInteractions(dagster);
    }

    @Test
    void triggerDagRejectsSparkRuntimeContractBeforeCreatingRun() {
        Dag dag = dag();
        dag.setDagsterJob("onelake_spark_operator_run");
        dag.setDefinition("{\"compileTarget\":\"SPARK\",\"engine\":\"SPARK\",\"nodes\":[]}");
        when(dagRepo.findByIdAndTenantId(DAG_ID, TENANT_ID)).thenReturn(Optional.of(dag));
        when(runtimeContractService.triggerBlockedReason(eq("onelake_spark_operator_run"), anyMap()))
            .thenReturn(Optional.of("SPARK 仍处于 Manifest 契约态，尚未接入 Dagster Spark op、依赖隔离和部署契约"));

        assertThatThrownBy(() -> service.triggerDag(DAG_ID, TriggerType.MANUAL))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("SPARK 仍处于 Manifest 契约态");

        verifyNoInteractions(runRepo);
        verifyNoInteractions(dagster);
    }

    @Test
    void triggerPipelineRunUsesLegacyJobByDefault() {
        List<DagStatus> statuses = captureRunStatuses();
        Dag dag = dag();
        dag.setDagsterJob("onelake_pipeline_run");
        PipelineTask task = pipelineTask("spark_node");
        when(dagRepo.findByIdAndTenantId(DAG_ID, TENANT_ID)).thenReturn(Optional.of(dag));
        when(pipelineCompileService.compile(DAG_ID)).thenReturn(pipelinePlan(task));
        when(pipelineTaskRepo.findByDagIdOrderByCreatedAtAsc(DAG_ID)).thenReturn(List.of(task));
        when(pipelineTaskEdgeRepo.findByDagId(DAG_ID)).thenReturn(List.of());
        ReflectionTestUtils.setField(service, "pipelineCallbackBaseUrl", "http://localhost:8080");
        when(sparkBuilder.build(any(), anyList(), eq("http://localhost:8080"),
                any(RunContext.class), anyMap(), anyList()))
                .thenReturn(new DagsterRunConfig("onelake_pipeline_run", Map.of("ops", Map.of())));
        when(dagster.launch(eq("onelake_pipeline_run"), eq("onelake"), eq("onelake-loc"), anyMap(), anyList()))
                .thenReturn("dagster-legacy");

        UUID runId = service.triggerPipelineRun(
                DAG_ID, TriggerType.MANUAL, RunEnvironment.DEV);

        assertThat(runId).isEqualTo(RUN_ID);
        assertThat(statuses).contains(DagStatus.QUEUED, DagStatus.RUNNING);
        verify(sparkBuilder).build(any(), anyList(), eq("http://localhost:8080"),
                any(RunContext.class), anyMap(), anyList());
        verify(sparkBuilder, never()).buildGraphRunConfig(
                any(), anyList(), anyList(), anyString(), anyInt(), anyMap(),
                any(RunContext.class), anyMap());
        verify(dagster).launch(eq("onelake_pipeline_run"), eq("onelake"), eq("onelake-loc"),
                anyMap(), anyList());
    }

    @Test
    void triggerPipelineRunUsesGraphJobForObserveOnlyPipeline() {
        List<DagStatus> statuses = captureRunStatuses();
        ReflectionTestUtils.setField(service, "pipelineExecutionMode", "GRAPH");
        ReflectionTestUtils.setField(service, "pipelineCallbackBaseUrl", "http://localhost:8080");
        ReflectionTestUtils.setField(service, "pipelineMaxParallel", 8);
        Dag dag = dag();
        dag.setDagsterJob("onelake_pipeline_run");
        PipelineTask task = pipelineTask("assert_only");
        task.setTaskType(TaskType.ASSERTION);
        task.setExecutable(false);
        task.setConfig("{\"expression\":\"true\"}");
        when(dagRepo.findByIdAndTenantId(DAG_ID, TENANT_ID)).thenReturn(Optional.of(dag));
        when(pipelineCompileService.compile(DAG_ID)).thenReturn(pipelinePlan(task));
        when(pipelineTaskRepo.findByDagIdOrderByCreatedAtAsc(DAG_ID)).thenReturn(List.of(task));
        when(pipelineTaskEdgeRepo.findByDagId(DAG_ID)).thenReturn(List.of());
        when(sparkBuilder.buildGraphRunConfig(any(), anyList(), anyList(),
                eq("http://localhost:8080"), eq(8), anyMap(), any(RunContext.class), anyMap()))
                .thenReturn(new DagsterRunConfig(GRAPH_JOB, Map.of("ops", Map.of())));
        when(dagster.launch(eq(GRAPH_JOB), eq("onelake"), eq("onelake-loc"), anyMap(), anyList()))
                .thenReturn("dagster-graph");

        UUID runId = service.triggerPipelineRun(
                DAG_ID, TriggerType.MANUAL, RunEnvironment.DEV);

        assertThat(runId).isEqualTo(RUN_ID);
        assertThat(statuses).contains(DagStatus.QUEUED, DagStatus.RUNNING);
        verify(sparkBuilder, never()).build(any(), anyList(), anyString(),
                any(RunContext.class), anyMap());
        verify(sparkBuilder).buildGraphRunConfig(any(), anyList(), anyList(),
                eq("http://localhost:8080"), eq(8), anyMap(), any(RunContext.class), anyMap());
        verify(dagster).launch(eq(GRAPH_JOB), eq("onelake"), eq("onelake-loc"),
                anyMap(), anyList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void deterministicEventTriggerLocksBeforeReusingExistingRun() {
        Dag dag = dag();
        JobRun existing = jobRun(DAG_ID);
        String eventKey = "sub-pipeline-attempt-key";
        when(dagRepo.findByIdAndTenantId(DAG_ID, TENANT_ID)).thenReturn(Optional.of(dag));
        when(runRepo.findByDagIdAndEventTriggerKey(DAG_ID, eventKey))
                .thenReturn(Optional.of(existing));

        UUID result = service.triggerPipelineRun(
                DAG_ID, TriggerType.EVENT, RunContext.empty(TriggerType.EVENT),
                UUID.randomUUID(), eventKey);

        assertThat(result).isEqualTo(RUN_ID);
        var ordered = inOrder(jdbc, runRepo);
        ordered.verify(jdbc).execute(any(ConnectionCallback.class));
        ordered.verify(runRepo).findByDagIdAndEventTriggerKey(DAG_ID, eventKey);
        verifyNoInteractions(dagster);
    }

    @Test
    @SuppressWarnings("unchecked")
    void graphInitialTaskStatusesLeaveSyncRefQueuedForDagsterCallback() {
        ReflectionTestUtils.setField(service, "pipelineExecutionMode", "GRAPH");
        PipelineTask syncRef = pipelineTask("sync_ref");
        syncRef.setTaskType(TaskType.SYNC_REF);
        PipelineTask spark = pipelineTask("spark_sql");

        Map<String, TaskRunStatus> statuses = ReflectionTestUtils.invokeMethod(
                service,
                "initialTaskRunStatuses",
                List.of(syncRef, spark),
                List.of(pipelineEdge("sync_ref", "spark_sql")));

        assertThat(statuses).containsOnly(
                org.assertj.core.data.MapEntry.entry("sync_ref", TaskRunStatus.QUEUED),
                org.assertj.core.data.MapEntry.entry("spark_sql", TaskRunStatus.QUEUED));
    }

    @Test
    void triggerPipelineRunValidatesGraphJobBeforeCreatingRun() {
        ReflectionTestUtils.setField(service, "pipelineExecutionMode", "GRAPH");
        Dag dag = dag();
        dag.setDagsterJob("onelake_pipeline_run");
        when(dagRepo.findByIdAndTenantId(DAG_ID, TENANT_ID)).thenReturn(Optional.of(dag));
        when(runtimeContractService.launchBlockedReason(eq(GRAPH_JOB), anyMap()))
                .thenReturn(Optional.of("Dagster repository 未暴露作业: " + GRAPH_JOB));

        assertThatThrownBy(() -> service.triggerPipelineRun(
                DAG_ID, TriggerType.MANUAL, RunEnvironment.DEV))
                .isInstanceOf(BizException.class)
                .hasMessageContaining(GRAPH_JOB);

        verify(pipelineCompileService, never()).compile(any());
        verifyNoInteractions(runRepo);
        verify(dagster).reloadRepositoryLocation("onelake-loc");
        verify(dagster, never()).launch(anyString(), anyString(), anyString(), anyMap(), anyList());
    }

    @Test
    void rerunTaskRejectsLegacyExecutionMode() {
        assertThatThrownBy(() -> service.rerunTask(DAG_ID, RUN_ID, "spark_node", "SINGLE"))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(40062))
                .hasMessageContaining("GRAPH");

        verifyNoInteractions(dagRepo, taskRunRepo, dagster);
    }

    @Test
    void extensionNodesRejectLegacyExecutionMode() {
        PipelineTask assertion = pipelineTask("assertion");
        assertion.setTaskType(TaskType.ASSERTION);

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                service, "validateTaskExecutionMode", List.of(assertion), TENANT_ID))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(40062))
                .hasMessageContaining("ASSERTION")
                .hasMessageContaining("GRAPH");
    }

    @Test
    void graphModeTreatsControlAndObserveNodesAsRunnableWithoutExecNode() {
        ReflectionTestUtils.setField(service, "pipelineExecutionMode", "GRAPH");
        PipelineTask assertion = pipelineTask("assertion");
        assertion.setTaskType(TaskType.ASSERTION);
        assertion.setExecutable(false);

        Boolean runnable = ReflectionTestUtils.invokeMethod(
                service, "hasRunnableTask", List.of(assertion));

        assertThat(runnable).isTrue();
    }

    @Test
    void rerunTaskRejectsNonFailedTaskRun() {
        ReflectionTestUtils.setField(service, "pipelineExecutionMode", "GRAPH");
        Dag dag = dag();
        JobRun run = jobRun(DAG_ID);
        TaskRun running = taskRun("spark_node", TaskRunStatus.RUNNING);
        when(dagRepo.findByIdAndTenantId(DAG_ID, TENANT_ID)).thenReturn(Optional.of(dag));
        when(runRepo.findByIdAndDagIdIn(eq(RUN_ID), eq(Set.of(DAG_ID)))).thenReturn(Optional.of(run));
        when(taskRunRepo.findByJobRunIdForUpdate(RUN_ID)).thenReturn(List.of(running));

        assertThatThrownBy(() -> service.rerunTask(DAG_ID, RUN_ID, "spark_node", "SINGLE"))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(40063))
                .hasMessageContaining("仅可对失败节点重跑");

        verify(pipelineCompileService, never()).compile(any());
        verify(dagster).reloadRepositoryLocation("onelake-loc");
        verify(dagster, never()).launch(anyString(), anyString(), anyString(), anyMap(), anyList());
    }

    @Test
    void rerunTaskSingleResetsOnlyTargetAndLaunchesSubgraph() {
        ReflectionTestUtils.setField(service, "pipelineExecutionMode", "GRAPH");
        Dag dag = dag();
        JobRun run = jobRun(DAG_ID);
        run.setStatus(DagStatus.FAILED);
        PipelineTask failedTask = pipelineTask("failed");
        PipelineTask downstreamTask = pipelineTask("downstream");
        TaskRun failed = taskRun("failed", TaskRunStatus.FAILED);
        failed.setAttempt(2);
        failed.setFinishedAt(Instant.parse("2026-07-09T01:02:00Z"));
        failed.setErrorMsg("boom");
        TaskRun downstream = taskRun("downstream", TaskRunStatus.UPSTREAM_FAILED);
        downstream.setAttempt(1);
        downstream.setErrorMsg("Upstream task failed: failed");
        when(dagRepo.findByIdAndTenantId(DAG_ID, TENANT_ID)).thenReturn(Optional.of(dag));
        when(runRepo.findByIdAndDagIdIn(eq(RUN_ID), eq(Set.of(DAG_ID)))).thenReturn(Optional.of(run));
        when(taskRunRepo.findByJobRunIdForUpdate(RUN_ID)).thenReturn(List.of(failed, downstream));
        when(pipelineCompileService.compile(DAG_ID)).thenReturn(pipelinePlan(failedTask, downstreamTask));
        when(pipelineTaskRepo.findByDagIdOrderByCreatedAtAsc(DAG_ID))
                .thenReturn(List.of(failedTask, downstreamTask));
        when(pipelineTaskEdgeRepo.findByDagId(DAG_ID))
                .thenReturn(List.of(pipelineEdge("failed", "downstream")));
        when(sparkBuilder.buildGraphRunConfig(any(), anyList(), anyList(), anyString(), anyInt(),
                anyMap(), any(RunContext.class), anyMap()))
                .thenReturn(new DagsterRunConfig(GRAPH_JOB, Map.of("ops", Map.of())));
        when(dagster.launch(eq(GRAPH_JOB), eq("onelake"), eq("onelake-loc"),
                anyMap(), anyList(), anyList())).thenReturn("dagster-rerun-single");

        var result = service.rerunTask(DAG_ID, RUN_ID, "failed", "SINGLE");

        assertThat(result.runId()).isEqualTo(RUN_ID);
        assertThat(result.rerunTasks()).containsExactly("failed");
        assertThat(result.dagsterRunId()).isEqualTo("dagster-rerun-single");
        assertThat(failed.getAttempt()).isEqualTo(3);
        assertThat(failed.getStatus()).isEqualTo(TaskRunStatus.RUNNING);
        assertThat(failed.getFinishedAt()).isNull();
        assertThat(failed.getErrorMsg()).isNull();
        assertThat(failed.getStartedAt()).isNotNull();
        assertThat(downstream.getAttempt()).isEqualTo(1);
        assertThat(downstream.getStatus()).isEqualTo(TaskRunStatus.UPSTREAM_FAILED);
        assertThat(downstream.getErrorMsg()).isEqualTo("Upstream task failed: failed");
        verify(dagster).launch(eq(GRAPH_JOB), eq("onelake"), eq("onelake-loc"),
                anyMap(), anyList(), eq(List.of("failed")));
        assertThat(run.getStatus()).isEqualTo(DagStatus.RUNNING);
        assertThat(run.getFinishedAt()).isNull();
        assertThat(run.getDagsterRunId()).isEqualTo("dagster-rerun-single");
        verify(sparkBuilder).buildGraphRunConfig(
                any(),
                argThat(tasks -> tasks.size() == 1 && "failed".equals(tasks.get(0).getTaskKey())),
                anyList(),
                eq(""),
                eq(4),
                argThat(baseAttempts -> baseAttempts.size() == 1
                        && Integer.valueOf(3).equals(baseAttempts.get("failed"))),
                any(RunContext.class),
                argThat(params -> RUN_ID.toString().equals(params.get("run_id"))));
    }

    @Test
    void rerunTaskOmitsBusinessTaskSelectionForFixedControlGraphJob() {
        ReflectionTestUtils.setField(service, "pipelineExecutionMode", "GRAPH");
        Dag dag = dag();
        JobRun run = jobRun(DAG_ID);
        run.setStatus(DagStatus.FAILED);
        PipelineTask conditionTask = pipelineTask("condition");
        conditionTask.setTaskType(TaskType.CONDITION);
        conditionTask.setExecutable(false);
        conditionTask.setConfig("{\"expression\":\"true\"}");
        TaskRun condition = taskRun("condition", TaskRunStatus.FAILED);
        when(dagRepo.findByIdAndTenantId(DAG_ID, TENANT_ID)).thenReturn(Optional.of(dag));
        when(runRepo.findByIdAndDagIdIn(RUN_ID, Set.of(DAG_ID))).thenReturn(Optional.of(run));
        when(taskRunRepo.findByJobRunIdForUpdate(RUN_ID)).thenReturn(List.of(condition));
        when(pipelineCompileService.compile(DAG_ID)).thenReturn(pipelinePlan(conditionTask));
        when(pipelineTaskRepo.findByDagIdOrderByCreatedAtAsc(DAG_ID))
                .thenReturn(List.of(conditionTask));
        when(pipelineTaskEdgeRepo.findByDagId(DAG_ID)).thenReturn(List.of());
        when(sparkBuilder.buildGraphRunConfig(any(), anyList(), anyList(), anyString(), anyInt(),
                anyMap(), any(RunContext.class), anyMap()))
                .thenReturn(new DagsterRunConfig(
                        "onelake_pipeline_graph_run", Map.of("ops", Map.of())));
        when(dagster.launch(eq("onelake_pipeline_graph_run"), eq("onelake"), eq("onelake-loc"),
                anyMap(), anyList(), eq(List.of()))).thenReturn("dagster-control-rerun");

        var result = service.rerunTask(
                DAG_ID, RUN_ID, "condition", "SINGLE");

        assertThat(result.dagsterRunId()).isEqualTo("dagster-control-rerun");
        assertThat(run.getDagsterJob()).isEqualTo("onelake_pipeline_graph_run");
        verify(dagster).launch(
                eq("onelake_pipeline_graph_run"), eq("onelake"), eq("onelake-loc"),
                anyMap(), anyList(), eq(List.of()));
    }

    @Test
    void rerunTaskUsesTheJobRunsBoundSnapshot() {
        ReflectionTestUtils.setField(service, "pipelineExecutionMode", "GRAPH");
        UUID versionId = UUID.randomUUID();
        Dag liveDag = dag();
        Dag snapshotDag = dag();
        JobRun run = jobRun(DAG_ID);
        run.setStatus(DagStatus.FAILED);
        run.setPipelineVersionId(versionId);
        PipelineTask snapshotTask = pipelineTask("failed");
        snapshotTask.setConfig("{\"sql\":\"SELECT 'published'\"}");
        TaskRun failed = taskRun("failed", TaskRunStatus.FAILED);
        PipelineParam frozenParam = new PipelineParam();
        frozenParam.setScope("PIPELINE");
        frozenParam.setParamKey("region");
        frozenParam.setParamValue("published");
        PipelineVersion version = new PipelineVersion();
        version.setId(versionId);
        version.setDagId(DAG_ID);
        version.setTenantId(TENANT_ID);
        when(dagRepo.findByIdAndTenantId(DAG_ID, TENANT_ID)).thenReturn(Optional.of(liveDag));
        when(runRepo.findByIdAndDagIdIn(RUN_ID, Set.of(DAG_ID))).thenReturn(Optional.of(run));
        when(pipelineSnapshotService.loadExecutionSnapshot(versionId, DAG_ID)).thenReturn(
                new PipelineSnapshotService.ExecutionSnapshot(
                        version, snapshotDag, List.of(snapshotTask), List.of(), List.of(frozenParam)));
        when(taskRunRepo.findByJobRunIdForUpdate(RUN_ID)).thenReturn(List.of(failed));
        when(pipelineCompileService.compile(
                eq(DAG_ID), eq(TENANT_ID), anyList(), anyList()))
                .thenReturn(pipelinePlan(snapshotTask));
        when(sparkBuilder.buildGraphRunConfig(any(), anyList(), anyList(), anyString(), anyInt(),
                anyMap(), any(RunContext.class), anyMap()))
                .thenReturn(new DagsterRunConfig(GRAPH_JOB, Map.of("ops", Map.of())));
        when(dagster.launch(eq(GRAPH_JOB), eq("onelake"), eq("onelake-loc"),
                anyMap(), anyList(), anyList())).thenReturn("dagster-rerun-snapshot");

        service.rerunTask(DAG_ID, RUN_ID, "failed", "SINGLE");

        org.mockito.InOrder definitionLoad = inOrder(pipelineSnapshotService, dagster);
        definitionLoad.verify(pipelineSnapshotService).activateForDagster(versionId);
        definitionLoad.verify(dagster).reloadRepositoryLocation("onelake-loc");
        verify(pipelineCompileService, never()).compile(DAG_ID);
        verify(pipelineTaskRepo, never()).findByDagIdOrderByCreatedAtAsc(DAG_ID);
        verify(pipelineTaskEdgeRepo, never()).findByDagId(DAG_ID);
        verify(sparkBuilder).buildGraphRunConfig(
                argThat(ctx -> ctx.frozenParams() != null
                        && ctx.frozenParams().size() == 1
                        && "published".equals(ctx.frozenParams().get(0).getParamValue())),
                argThat(tasks -> tasks.size() == 1
                        && tasks.get(0).getConfig().contains("published")),
                anyList(), anyString(), anyInt(), anyMap(), any(RunContext.class), anyMap());
    }

    @Test
    void rerunTaskDownstreamResetsFailedNodeAndNonSucceededDescendants() {
        ReflectionTestUtils.setField(service, "pipelineExecutionMode", "GRAPH");
        Dag dag = dag();
        JobRun run = jobRun(DAG_ID);
        run.setStatus(DagStatus.FAILED);
        PipelineTask rootTask = pipelineTask("root");
        PipelineTask leftTask = pipelineTask("left");
        PipelineTask rightTask = pipelineTask("right");
        PipelineTask joinTask = pipelineTask("join");
        TaskRun root = taskRun("root", TaskRunStatus.SUCCEEDED);
        TaskRun left = taskRun("left", TaskRunStatus.FAILED);
        left.setAttempt(2);
        left.setFinishedAt(Instant.parse("2026-07-09T01:02:00Z"));
        left.setErrorMsg("left failed");
        TaskRun right = taskRun("right", TaskRunStatus.SUCCEEDED);
        TaskRun join = taskRun("join", TaskRunStatus.UPSTREAM_FAILED);
        join.setAttempt(1);
        join.setErrorMsg("Upstream task failed: left");
        List<PipelineTaskEdge> edges = List.of(
                pipelineEdge("root", "left"),
                pipelineEdge("root", "right"),
                pipelineEdge("left", "join"),
                pipelineEdge("right", "join"));
        when(dagRepo.findByIdAndTenantId(DAG_ID, TENANT_ID)).thenReturn(Optional.of(dag));
        when(runRepo.findByIdAndDagIdIn(eq(RUN_ID), eq(Set.of(DAG_ID)))).thenReturn(Optional.of(run));
        when(taskRunRepo.findByJobRunIdForUpdate(RUN_ID)).thenReturn(List.of(root, left, right, join));
        when(pipelineCompileService.compile(DAG_ID))
                .thenReturn(pipelinePlan(rootTask, leftTask, rightTask, joinTask));
        when(pipelineTaskRepo.findByDagIdOrderByCreatedAtAsc(DAG_ID))
                .thenReturn(List.of(rootTask, leftTask, rightTask, joinTask));
        when(pipelineTaskEdgeRepo.findByDagId(DAG_ID)).thenReturn(edges);
        when(sparkBuilder.buildGraphRunConfig(any(), anyList(), anyList(), anyString(), anyInt(),
                anyMap(), any(RunContext.class), anyMap()))
                .thenReturn(new DagsterRunConfig(GRAPH_JOB, Map.of("ops", Map.of())));
        when(dagster.launch(eq(GRAPH_JOB), eq("onelake"), eq("onelake-loc"),
                anyMap(), anyList(), anyList())).thenReturn("dagster-rerun-downstream");

        var result = service.rerunTask(DAG_ID, RUN_ID, "left", "DOWNSTREAM");

        assertThat(result.rerunTasks()).containsExactly("left", "join");
        assertThat(left.getAttempt()).isEqualTo(3);
        assertThat(left.getStatus()).isEqualTo(TaskRunStatus.RUNNING);
        assertThat(join.getAttempt()).isEqualTo(2);
        assertThat(join.getStatus()).isEqualTo(TaskRunStatus.QUEUED);
        assertThat(join.getStartedAt()).isNull();
        assertThat(join.getFinishedAt()).isNull();
        assertThat(join.getErrorMsg()).isNull();
        assertThat(root.getStatus()).isEqualTo(TaskRunStatus.SUCCEEDED);
        assertThat(right.getStatus()).isEqualTo(TaskRunStatus.SUCCEEDED);
        verify(sparkBuilder).buildGraphRunConfig(
                any(),
                argThat(tasks -> tasks.stream().map(PipelineTask::getTaskKey).toList()
                        .equals(List.of("left", "join"))),
                eq(edges),
                eq(""),
                eq(4),
                argThat(baseAttempts -> baseAttempts.equals(Map.of("left", 3, "join", 2))),
                any(RunContext.class),
                argThat(params -> RUN_ID.toString().equals(params.get("run_id"))));
    }

    @Test
    void rerunTaskDownstreamAllowsSucceededRootWhenBlockedDescendantCanResume() {
        ReflectionTestUtils.setField(service, "pipelineExecutionMode", "GRAPH");
        Dag dag = dag();
        JobRun run = jobRun(DAG_ID);
        run.setStatus(DagStatus.FAILED);
        PipelineTask upstreamTask = pipelineTask("sync_ref");
        PipelineTask sparkTask = pipelineTask("spark_sql");
        PipelineTask gateTask = pipelineTask("quality_gate");
        TaskRun upstream = taskRun("sync_ref", TaskRunStatus.SUCCEEDED);
        TaskRun spark = taskRun("spark_sql", TaskRunStatus.SUCCEEDED);
        spark.setAttempt(2);
        TaskRun gate = taskRun("quality_gate", TaskRunStatus.UPSTREAM_FAILED);
        gate.setAttempt(1);
        gate.setErrorMsg("Upstream task failed: spark_sql");
        List<PipelineTaskEdge> edges = List.of(
                pipelineEdge("sync_ref", "spark_sql"),
                pipelineEdge("spark_sql", "quality_gate"));
        when(dagRepo.findByIdAndTenantId(DAG_ID, TENANT_ID)).thenReturn(Optional.of(dag));
        when(runRepo.findByIdAndDagIdIn(eq(RUN_ID), eq(Set.of(DAG_ID)))).thenReturn(Optional.of(run));
        when(taskRunRepo.findByJobRunIdForUpdate(RUN_ID)).thenReturn(List.of(upstream, spark, gate));
        when(pipelineTaskEdgeRepo.findByDagId(DAG_ID)).thenReturn(edges);
        when(pipelineCompileService.compile(DAG_ID))
                .thenReturn(pipelinePlan(upstreamTask, sparkTask, gateTask));
        when(pipelineTaskRepo.findByDagIdOrderByCreatedAtAsc(DAG_ID))
                .thenReturn(List.of(upstreamTask, sparkTask, gateTask));
        when(sparkBuilder.buildGraphRunConfig(any(), anyList(), anyList(), anyString(), anyInt(),
                anyMap(), any(RunContext.class), anyMap()))
                .thenReturn(new DagsterRunConfig(GRAPH_JOB, Map.of("ops", Map.of())));
        when(dagster.launch(eq(GRAPH_JOB), eq("onelake"), eq("onelake-loc"),
                anyMap(), anyList(), anyList())).thenReturn("dagster-rerun-resume");

        var result = service.rerunTask(DAG_ID, RUN_ID, "spark_sql", "DOWNSTREAM");

        assertThat(result.rerunTasks()).containsExactly("spark_sql", "quality_gate");
        assertThat(upstream.getAttempt()).isEqualTo(1);
        assertThat(upstream.getStatus()).isEqualTo(TaskRunStatus.SUCCEEDED);
        assertThat(spark.getAttempt()).isEqualTo(3);
        assertThat(spark.getStatus()).isEqualTo(TaskRunStatus.RUNNING);
        assertThat(gate.getAttempt()).isEqualTo(2);
        assertThat(gate.getStatus()).isEqualTo(TaskRunStatus.QUEUED);
        verify(sparkBuilder).buildGraphRunConfig(
                any(),
                argThat(tasks -> tasks.stream().map(PipelineTask::getTaskKey).toList()
                        .equals(List.of("spark_sql", "quality_gate"))),
                eq(edges),
                eq(""),
                eq(4),
                argThat(baseAttempts -> baseAttempts.equals(Map.of("spark_sql", 3, "quality_gate", 2))),
                any(RunContext.class),
                argThat(params -> RUN_ID.toString().equals(params.get("run_id"))));
    }

    @Test
    void rerunTaskDownstreamRejectsFailedBranchWithFailedExternalInput() {
        ReflectionTestUtils.setField(service, "pipelineExecutionMode", "GRAPH");
        Dag dag = dag();
        JobRun run = jobRun(DAG_ID);
        run.setStatus(DagStatus.FAILED);
        TaskRun root = taskRun("root", TaskRunStatus.SUCCEEDED);
        TaskRun left = taskRun("left", TaskRunStatus.FAILED);
        TaskRun right = taskRun("right", TaskRunStatus.FAILED);
        TaskRun join = taskRun("join", TaskRunStatus.UPSTREAM_FAILED);
        List<PipelineTaskEdge> edges = List.of(
                pipelineEdge("root", "left"),
                pipelineEdge("root", "right"),
                pipelineEdge("left", "join"),
                pipelineEdge("right", "join"));
        when(dagRepo.findByIdAndTenantId(DAG_ID, TENANT_ID)).thenReturn(Optional.of(dag));
        when(runRepo.findByIdAndDagIdIn(eq(RUN_ID), eq(Set.of(DAG_ID)))).thenReturn(Optional.of(run));
        when(taskRunRepo.findByJobRunIdForUpdate(RUN_ID)).thenReturn(List.of(root, left, right, join));
        when(pipelineTaskEdgeRepo.findByDagId(DAG_ID)).thenReturn(edges);

        assertThatThrownBy(() -> service.rerunTask(DAG_ID, RUN_ID, "left", "DOWNSTREAM"))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(40063))
                .hasMessageContaining("从失败续跑存在未成功的外部上游: right -> join");

        assertThat(left.getStatus()).isEqualTo(TaskRunStatus.FAILED);
        assertThat(right.getStatus()).isEqualTo(TaskRunStatus.FAILED);
        assertThat(join.getStatus()).isEqualTo(TaskRunStatus.UPSTREAM_FAILED);
        verify(pipelineCompileService, never()).compile(any());
        verify(dagster).reloadRepositoryLocation("onelake-loc");
        verify(dagster, never()).launch(anyString(), anyString(), anyString(), anyMap(), anyList());
    }

    @Test
    void rerunTaskDownstreamRejectsSucceededBranchWithFailedExternalInput() {
        ReflectionTestUtils.setField(service, "pipelineExecutionMode", "GRAPH");
        Dag dag = dag();
        JobRun run = jobRun(DAG_ID);
        run.setStatus(DagStatus.FAILED);
        TaskRun root = taskRun("root", TaskRunStatus.SUCCEEDED);
        TaskRun left = taskRun("left", TaskRunStatus.FAILED);
        TaskRun right = taskRun("right", TaskRunStatus.SUCCEEDED);
        TaskRun join = taskRun("join", TaskRunStatus.UPSTREAM_FAILED);
        List<PipelineTaskEdge> edges = List.of(
                pipelineEdge("root", "left"),
                pipelineEdge("root", "right"),
                pipelineEdge("left", "join"),
                pipelineEdge("right", "join"));
        when(dagRepo.findByIdAndTenantId(DAG_ID, TENANT_ID)).thenReturn(Optional.of(dag));
        when(runRepo.findByIdAndDagIdIn(eq(RUN_ID), eq(Set.of(DAG_ID)))).thenReturn(Optional.of(run));
        when(taskRunRepo.findByJobRunIdForUpdate(RUN_ID)).thenReturn(List.of(root, left, right, join));
        when(pipelineTaskEdgeRepo.findByDagId(DAG_ID)).thenReturn(edges);

        assertThatThrownBy(() -> service.rerunTask(DAG_ID, RUN_ID, "right", "DOWNSTREAM"))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(40063))
                .hasMessageContaining("从失败续跑存在未成功的外部上游: left -> join");

        verify(pipelineCompileService, never()).compile(any());
        verify(dagster).reloadRepositoryLocation("onelake-loc");
        verify(dagster, never()).launch(anyString(), anyString(), anyString(), anyMap(), anyList());
    }

    @Test
    void rerunTaskMarksSubgraphFailedWhenDagsterLaunchFails() {
        ReflectionTestUtils.setField(service, "pipelineExecutionMode", "GRAPH");
        Dag dag = dag();
        JobRun run = jobRun(DAG_ID);
        run.setStatus(DagStatus.FAILED);
        PipelineTask failedTask = pipelineTask("failed");
        PipelineTask downstreamTask = pipelineTask("downstream");
        TaskRun failed = taskRun("failed", TaskRunStatus.FAILED);
        TaskRun downstream = taskRun("downstream", TaskRunStatus.UPSTREAM_FAILED);
        when(dagRepo.findByIdAndTenantId(DAG_ID, TENANT_ID)).thenReturn(Optional.of(dag));
        when(runRepo.findByIdAndDagIdIn(eq(RUN_ID), eq(Set.of(DAG_ID)))).thenReturn(Optional.of(run));
        when(taskRunRepo.findByJobRunIdForUpdate(RUN_ID)).thenReturn(List.of(failed, downstream));
        when(pipelineCompileService.compile(DAG_ID)).thenReturn(pipelinePlan(failedTask, downstreamTask));
        when(pipelineTaskRepo.findByDagIdOrderByCreatedAtAsc(DAG_ID))
                .thenReturn(List.of(failedTask, downstreamTask));
        when(pipelineTaskEdgeRepo.findByDagId(DAG_ID))
                .thenReturn(List.of(pipelineEdge("failed", "downstream")));
        when(sparkBuilder.buildGraphRunConfig(any(), anyList(), anyList(), anyString(), anyInt(),
                anyMap(), any(RunContext.class), anyMap()))
                .thenReturn(new DagsterRunConfig(GRAPH_JOB, Map.of("ops", Map.of())));
        when(dagster.launch(eq(GRAPH_JOB), eq("onelake"), eq("onelake-loc"),
                anyMap(), anyList(), anyList())).thenThrow(new RuntimeException("connection refused"));

        assertThatThrownBy(() -> service.rerunTask(DAG_ID, RUN_ID, "failed", "DOWNSTREAM"))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(50200))
                .hasMessageContaining("Dagster 重跑触发失败");

        assertThat(run.getStatus()).isEqualTo(DagStatus.FAILED);
        assertThat(run.getDagsterRunId()).isNull();
        assertThat(run.getFinishedAt()).isNotNull();
        assertThat(failed.getStatus()).isEqualTo(TaskRunStatus.FAILED);
        assertThat(failed.getErrorMsg()).contains("Dagster rerun launch failed");
        assertThat(failed.getFinishedAt()).isNotNull();
        assertThat(downstream.getStatus()).isEqualTo(TaskRunStatus.FAILED);
        assertThat(downstream.getErrorMsg()).contains("connection refused");
        assertThat(downstream.getFinishedAt()).isNotNull();
    }

    @Test
    void applyTaskRunCallbackRefreshesRunningIdempotently() {
        JobRun run = jobRun(DAG_ID);
        run.setStatus(DagStatus.RUNNING);
        run.setFinishedAt(null);
        Dag dag = dag();
        TaskRun taskRun = taskRun("spark_node", TaskRunStatus.RUNNING);
        Instant startedAt = Instant.parse("2026-07-09T01:00:00Z");
        when(runRepo.findById(RUN_ID)).thenReturn(Optional.of(run));
        when(dagRepo.findById(DAG_ID)).thenReturn(Optional.of(dag));
        when(taskRunRepo.findByJobRunIdAndTaskKeyForUpdate(RUN_ID, "spark_node"))
                .thenReturn(Optional.of(taskRun));

        TaskRunCallbackResult result = service.applyTaskRunCallback(RUN_ID, "spark_node",
                new TaskRunCallbackRequest(
                        TaskRunStatus.RUNNING,
                        startedAt,
                        null,
                        null,
                        null,
                        12L,
                        34L,
                        "s3://logs/run/spark_node.log",
                        2,
                        "spark_node_step",
                        null));

        assertThat(result.applied()).isTrue();
        assertThat(result.currentStatus()).isEqualTo(TaskRunStatus.RUNNING);
        assertThat(taskRun.getStatus()).isEqualTo(TaskRunStatus.RUNNING);
        assertThat(taskRun.getStartedAt()).isEqualTo(startedAt);
        assertThat(taskRun.getRowsWritten()).isEqualTo(12L);
        assertThat(taskRun.getScanBytes()).isEqualTo(34L);
        assertThat(taskRun.getLogRef()).isEqualTo("s3://logs/run/spark_node.log");
        assertThat(taskRun.getAttempt()).isEqualTo(2);
        assertThat(taskRun.getDagsterStepKey()).isEqualTo("spark_node_step");
        verify(taskRunRepo).save(taskRun);
        verify(pipelineTaskEdgeRepo, never()).findByDagId(any());
    }

    @Test
    void applyTaskRunCallbackKeepsTerminalTaskRunImmutable() {
        JobRun run = jobRun(DAG_ID);
        Dag dag = dag();
        TaskRun taskRun = taskRun("spark_node", TaskRunStatus.SUCCEEDED);
        taskRun.setRowsWritten(10L);
        when(runRepo.findById(RUN_ID)).thenReturn(Optional.of(run));
        when(dagRepo.findById(DAG_ID)).thenReturn(Optional.of(dag));
        when(taskRunRepo.findByJobRunIdForUpdate(RUN_ID)).thenReturn(List.of(taskRun));

        TaskRunCallbackResult result = service.applyTaskRunCallback(RUN_ID, "spark_node",
                new TaskRunCallbackRequest(
                        TaskRunStatus.FAILED,
                        null,
                        Instant.parse("2026-07-09T01:05:00Z"),
                        "late failure",
                        null,
                        0L,
                        null,
                        "new-log",
                        3,
                        "new-step",
                        null));

        assertThat(result.applied()).isFalse();
        assertThat(result.currentStatus()).isEqualTo(TaskRunStatus.SUCCEEDED);
        assertThat(taskRun.getStatus()).isEqualTo(TaskRunStatus.SUCCEEDED);
        assertThat(taskRun.getRowsWritten()).isEqualTo(10L);
        assertThat(taskRun.getLogRef()).isNull();
        verify(taskRunRepo, never()).save(any(TaskRun.class));
    }

    @Test
    void applyTaskRunCallbackRejectsStatusRankRollback() {
        JobRun run = jobRun(DAG_ID);
        Dag dag = dag();
        TaskRun taskRun = taskRun("spark_node", TaskRunStatus.RUNNING);
        when(runRepo.findById(RUN_ID)).thenReturn(Optional.of(run));
        when(dagRepo.findById(DAG_ID)).thenReturn(Optional.of(dag));
        when(taskRunRepo.findByJobRunIdAndTaskKeyForUpdate(RUN_ID, "spark_node"))
                .thenReturn(Optional.of(taskRun));

        TaskRunCallbackResult result = service.applyTaskRunCallback(RUN_ID, "spark_node",
                new TaskRunCallbackRequest(
                        TaskRunStatus.QUEUED,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "rollback-log",
                        null,
                        null,
                        null));

        assertThat(result.applied()).isFalse();
        assertThat(result.currentStatus()).isEqualTo(TaskRunStatus.RUNNING);
        assertThat(taskRun.getLogRef()).isNull();
        verify(taskRunRepo, never()).save(any(TaskRun.class));
    }

    @Test
    void applyTaskRunCallbackRejectsStaleAttempt() {
        JobRun run = jobRun(DAG_ID);
        Dag dag = dag();
        TaskRun taskRun = taskRun("spark_node", TaskRunStatus.RUNNING);
        taskRun.setAttempt(3);
        when(runRepo.findById(RUN_ID)).thenReturn(Optional.of(run));
        when(dagRepo.findById(DAG_ID)).thenReturn(Optional.of(dag));
        when(taskRunRepo.findByJobRunIdForUpdate(RUN_ID)).thenReturn(List.of(taskRun));

        TaskRunCallbackResult result = service.applyTaskRunCallback(RUN_ID, "spark_node",
                new TaskRunCallbackRequest(
                        TaskRunStatus.FAILED,
                        null,
                        Instant.parse("2026-07-09T01:02:00Z"),
                        "late failure",
                        null,
                        null,
                        null,
                        "stale-log",
                        2,
                        null,
                        null));

        assertThat(result.applied()).isFalse();
        assertThat(result.currentStatus()).isEqualTo(TaskRunStatus.RUNNING);
        assertThat(taskRun.getAttempt()).isEqualTo(3);
        assertThat(taskRun.getStatus()).isEqualTo(TaskRunStatus.RUNNING);
        assertThat(taskRun.getErrorMsg()).isNull();
        verify(taskRunRepo, never()).save(any(TaskRun.class));
    }

    @Test
    void applyTaskRunCallbackPersistsTerminalFields() {
        JobRun run = jobRun(DAG_ID);
        run.setStatus(DagStatus.RUNNING);
        Dag dag = dag();
        TaskRun taskRun = taskRun("spark_node", TaskRunStatus.RUNNING);
        Instant startedAt = Instant.parse("2026-07-09T01:00:00Z");
        Instant finishedAt = Instant.parse("2026-07-09T01:02:00Z");
        when(runRepo.findById(RUN_ID)).thenReturn(Optional.of(run));
        when(dagRepo.findById(DAG_ID)).thenReturn(Optional.of(dag));
        when(taskRunRepo.findByJobRunIdAndTaskKeyForUpdate(RUN_ID, "spark_node"))
                .thenReturn(Optional.of(taskRun));
        var callbackOutputs = JsonUtil.mapper().createObjectNode();
        callbackOutputs.put("partition", "2026-07-09");

        TaskRunCallbackResult result = service.applyTaskRunCallback(RUN_ID, "spark_node",
                new TaskRunCallbackRequest(
                        TaskRunStatus.SUCCEEDED,
                        startedAt,
                        finishedAt,
                        null,
                        "table:dwd.spark_node",
                        88L,
                        4096L,
                        "s3://logs/run/spark_node.log",
                        1,
                        "spark_node_step",
                        callbackOutputs));

        assertThat(result.applied()).isTrue();
        assertThat(taskRun.getStatus()).isEqualTo(TaskRunStatus.SUCCEEDED);
        assertThat(taskRun.getStartedAt()).isEqualTo(startedAt);
        assertThat(taskRun.getFinishedAt()).isEqualTo(finishedAt);
        assertThat(taskRun.getArtifactPath()).isEqualTo("table:dwd.spark_node");
        assertThat(taskRun.getRowsWritten()).isEqualTo(88L);
        assertThat(taskRun.getScanBytes()).isEqualTo(4096L);
        assertThat(taskRun.getLogRef()).isEqualTo("s3://logs/run/spark_node.log");
        assertThat(taskRun.getDagsterStepKey()).isEqualTo("spark_node_step");
        assertThat(JsonUtil.fromJson(taskRun.getOutputs(), Map.class))
                .containsEntry("rowsWritten", 88)
                .containsEntry("artifactPath", "table:dwd.spark_node")
                .containsEntry("partition", "2026-07-09");
        verify(taskRunRepo).save(taskRun);
    }

    @Test
    void applyTaskRunCallbackNormalizesReservedFieldsFromOutputs() {
        JobRun run = jobRun(DAG_ID);
        run.setStatus(DagStatus.RUNNING);
        TaskRun taskRun = taskRun("spark_node", TaskRunStatus.RUNNING);
        when(runRepo.findById(RUN_ID)).thenReturn(Optional.of(run));
        when(dagRepo.findById(DAG_ID)).thenReturn(Optional.of(dag()));
        when(taskRunRepo.findByJobRunIdAndTaskKeyForUpdate(RUN_ID, "spark_node"))
                .thenReturn(Optional.of(taskRun));
        var outputs = JsonUtil.mapper().createObjectNode();
        outputs.put("rowsWritten", 9L);
        outputs.put("artifactPath", "table:dwd.spark_node");

        service.applyTaskRunCallback(RUN_ID, "spark_node", new TaskRunCallbackRequest(
                TaskRunStatus.SUCCEEDED,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                1,
                "spark_node",
                outputs));

        assertThat(taskRun.getRowsWritten()).isEqualTo(9L);
        assertThat(taskRun.getArtifactPath()).isEqualTo("table:dwd.spark_node");
        assertThat(JsonUtil.fromJson(taskRun.getOutputs(), Map.class))
                .containsEntry("rowsWritten", 9)
                .containsEntry("artifactPath", "table:dwd.spark_node");
    }

    @Test
    void applyTaskRunCallbackRejectsInvalidReservedOutputType() {
        JobRun run = jobRun(DAG_ID);
        TaskRun taskRun = taskRun("spark_node", TaskRunStatus.RUNNING);
        when(runRepo.findById(RUN_ID)).thenReturn(Optional.of(run));
        when(dagRepo.findById(DAG_ID)).thenReturn(Optional.of(dag()));
        when(taskRunRepo.findByJobRunIdAndTaskKeyForUpdate(RUN_ID, "spark_node"))
                .thenReturn(Optional.of(taskRun));
        var outputs = JsonUtil.mapper().createObjectNode();
        outputs.put("rowsWritten", "invalid");

        assertThatThrownBy(() -> service.applyTaskRunCallback(
                RUN_ID, "spark_node", new TaskRunCallbackRequest(
                        TaskRunStatus.SUCCEEDED,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        1,
                        "spark_node",
                        outputs)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("outputs.rowsWritten");
        verify(taskRunRepo, never()).save(any(TaskRun.class));
    }

    @Test
    void renderTaskConfigReadsSucceededUpstreamOutputsFromSameJobRun() throws Exception {
        JobRun run = jobRun(DAG_ID);
        TaskRun upstream = taskRun("extract", TaskRunStatus.SUCCEEDED);
        upstream.setOutputs("{\"rowsWritten\":88,\"artifactPath\":\"table:dwd.orders\","
                + "\"custom\":{\"partition\":\"20260709\"}}");
        TaskRun downstream = taskRun("quality_gate", TaskRunStatus.QUEUED);
        when(runRepo.findById(RUN_ID)).thenReturn(Optional.of(run));
        when(taskRunRepo.findByJobRunId(RUN_ID)).thenReturn(List.of(upstream, downstream));

        var config = JsonUtil.mapper().readTree("""
                {"sql_or_script":"assert ${upstream.extract.rowsWritten} >= 0",
                 "config":{"path":"${upstream.extract.artifactPath}",
                           "partition":"${upstream.extract.custom.partition}"}}
                """);

        var result = service.renderTaskConfig(
                RUN_ID, "quality_gate", config, List.of("extract"));

        assertThat(result.config().path("sql_or_script").asText()).isEqualTo("assert 88 >= 0");
        assertThat(result.config().path("config").path("path").asText())
                .isEqualTo("table:dwd.orders");
        assertThat(result.config().path("config").path("partition").asText())
                .isEqualTo("20260709");
        verify(pipelineTaskEdgeRepo, never()).findByDagId(DAG_ID);
    }

    @Test
    void renderTaskConfigReportsMissingUpstreamFieldClearly() throws Exception {
        JobRun run = jobRun(DAG_ID);
        TaskRun upstream = taskRun("extract", TaskRunStatus.SUCCEEDED);
        upstream.setOutputs("{\"artifactPath\":\"table:dwd.orders\"}");
        TaskRun downstream = taskRun("quality_gate", TaskRunStatus.QUEUED);
        when(runRepo.findById(RUN_ID)).thenReturn(Optional.of(run));
        when(taskRunRepo.findByJobRunId(RUN_ID)).thenReturn(List.of(upstream, downstream));
        var config = JsonUtil.mapper().readTree(
                "{\"sql_or_script\":\"${upstream.extract.rowsWritten}\"}");

        assertThatThrownBy(() -> service.renderTaskConfig(
                RUN_ID, "quality_gate", config, List.of("extract")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("extract")
                .hasMessageContaining("outputs 缺少字段 rowsWritten");
    }

    @Test
    void renderTaskConfigRejectsSucceededTaskThatIsNotAGraphAncestor() throws Exception {
        JobRun run = jobRun(DAG_ID);
        TaskRun sibling = taskRun("sibling", TaskRunStatus.SUCCEEDED);
        sibling.setOutputs("{\"rowsWritten\":88}");
        TaskRun downstream = taskRun("quality_gate", TaskRunStatus.QUEUED);
        when(runRepo.findById(RUN_ID)).thenReturn(Optional.of(run));
        when(taskRunRepo.findByJobRunId(RUN_ID)).thenReturn(List.of(sibling, downstream));
        var config = JsonUtil.mapper().readTree(
                "{\"sql_or_script\":\"${upstream.sibling.rowsWritten}\"}");

        assertThatThrownBy(() -> service.renderTaskConfig(
                RUN_ID, "quality_gate", config, List.of()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("sibling")
                .hasMessageContaining("不是当前节点的图上游");
    }

    @Test
    void applyTaskRunCallbackShortCircuitsQueuedDownstreamFromFailedSeed() {
        JobRun run = jobRun(DAG_ID);
        run.setStatus(DagStatus.RUNNING);
        Dag dag = dag();
        TaskRun failed = taskRun("extract", TaskRunStatus.RUNNING);
        TaskRun downstream = taskRun("load", TaskRunStatus.QUEUED);
        PipelineTaskEdge edge = pipelineEdge("extract", "load");
        when(runRepo.findById(RUN_ID)).thenReturn(Optional.of(run));
        when(dagRepo.findById(DAG_ID)).thenReturn(Optional.of(dag));
        when(taskRunRepo.findByJobRunIdForUpdate(RUN_ID)).thenReturn(List.of(failed, downstream));
        when(pipelineTaskEdgeRepo.findByDagId(DAG_ID)).thenReturn(List.of(edge));

        TaskRunCallbackResult result = service.applyTaskRunCallback(RUN_ID, "extract",
                new TaskRunCallbackRequest(
                        TaskRunStatus.FAILED,
                        null,
                        Instant.parse("2026-07-09T01:02:00Z"),
                        "boom",
                        null,
                        null,
                        null,
                        "s3://logs/run/extract.log",
                        1,
                        "extract_step",
                        null));

        assertThat(result.applied()).isTrue();
        assertThat(failed.getStatus()).isEqualTo(TaskRunStatus.FAILED);
        assertThat(failed.getErrorMsg()).isEqualTo("boom");
        assertThat(downstream.getStatus()).isEqualTo(TaskRunStatus.UPSTREAM_FAILED);
        assertThat(downstream.getErrorMsg()).isEqualTo("Upstream task failed: extract");
        assertThat(downstream.getFinishedAt()).isNotNull();
        verify(taskRunRepo).save(failed);
        verify(taskRunRepo).save(downstream);
    }

    @Test
    void failedInternalCallbackLoadsPublishedTopologyWithoutUserTenantContext() {
        UUID versionId = UUID.randomUUID();
        JobRun run = jobRun(DAG_ID);
        run.setStatus(DagStatus.RUNNING);
        run.setPipelineVersionId(versionId);
        Dag dag = dag();
        TaskRun failed = taskRun("observe", TaskRunStatus.RUNNING);
        TaskRun downstream = taskRun("downstream", TaskRunStatus.QUEUED);
        PipelineTaskEdge edge = pipelineEdge("observe", "downstream");
        PipelineVersion version = new PipelineVersion();
        version.setId(versionId);
        version.setDagId(DAG_ID);
        version.setTenantId(TENANT_ID);
        when(runRepo.findById(RUN_ID)).thenReturn(Optional.of(run));
        when(dagRepo.findById(DAG_ID)).thenReturn(Optional.of(dag));
        when(taskRunRepo.findByJobRunIdForUpdate(RUN_ID)).thenReturn(List.of(failed, downstream));
        when(pipelineSnapshotService.loadExecutionSnapshotForRuntime(versionId, DAG_ID, TENANT_ID))
                .thenReturn(new PipelineSnapshotService.ExecutionSnapshot(
                        version, dag, List.of(), List.of(edge), List.of()));
        TenantContext.clear();

        TaskRunCallbackResult result = service.applyTaskRunCallback(RUN_ID, "observe",
                new TaskRunCallbackRequest(
                        TaskRunStatus.FAILED,
                        null,
                        Instant.parse("2026-07-12T13:36:25Z"),
                        "SENSOR timed out after 3s",
                        null,
                        null,
                        null,
                        TENANT_ID + "/" + RUN_ID + "/observe/latest.log",
                        1,
                        "observe",
                        null));

        assertThat(result.applied()).isTrue();
        assertThat(failed.getStatus()).isEqualTo(TaskRunStatus.FAILED);
        assertThat(failed.getErrorMsg()).isEqualTo("SENSOR timed out after 3s");
        assertThat(failed.getLogRef()).endsWith("/observe/latest.log");
        assertThat(downstream.getStatus()).isEqualTo(TaskRunStatus.UPSTREAM_FAILED);
    }

    @Test
    void applyTaskRunCallbackKeepsIntentionalSkipDistinctFromUpstreamFailure() {
        JobRun run = jobRun(DAG_ID);
        run.setStatus(DagStatus.RUNNING);
        TaskRun skipped = taskRun("not_selected", TaskRunStatus.QUEUED);
        TaskRun downstream = taskRun("join", TaskRunStatus.QUEUED);
        when(runRepo.findById(RUN_ID)).thenReturn(Optional.of(run));
        when(dagRepo.findById(DAG_ID)).thenReturn(Optional.of(dag()));
        when(taskRunRepo.findByJobRunIdAndTaskKeyForUpdate(RUN_ID, "not_selected"))
                .thenReturn(Optional.of(skipped));

        TaskRunCallbackResult result = service.applyTaskRunCallback(RUN_ID, "not_selected",
                new TaskRunCallbackRequest(
                        TaskRunStatus.SKIPPED,
                        null,
                        Instant.parse("2026-07-12T01:02:00Z"),
                        null,
                        null,
                        null,
                        null,
                        "s3://logs/run/not_selected.log",
                        1,
                        "not_selected",
                        null));

        assertThat(result.applied()).isTrue();
        assertThat(skipped.getStatus()).isEqualTo(TaskRunStatus.SKIPPED);
        assertThat(downstream.getStatus()).isEqualTo(TaskRunStatus.QUEUED);
        verify(taskRunRepo).save(skipped);
        verify(taskRunRepo, never()).findByJobRunIdForUpdate(RUN_ID);
        verify(pipelineTaskEdgeRepo, never()).findByDagId(DAG_ID);
    }

    @Test
    void readTaskRunLogRejectsEmptyLogRef() {
        Dag dag = dag();
        JobRun run = jobRun(DAG_ID);
        TaskRun taskRun = taskRun("spark_node", TaskRunStatus.SUCCEEDED);
        when(dagRepo.findByIdAndTenantId(DAG_ID, TENANT_ID)).thenReturn(Optional.of(dag));
        when(runRepo.findByIdAndDagIdIn(eq(RUN_ID), argThat(ids -> ids != null && ids.contains(DAG_ID))))
                .thenReturn(Optional.of(run));
        when(taskRunRepo.findByJobRunIdAndTaskKey(RUN_ID, "spark_node")).thenReturn(Optional.of(taskRun));

        assertThatThrownBy(() -> service.readTaskRunLog(DAG_ID, RUN_ID, "spark_node", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("节点日志不存在");

        verifyNoInteractions(pipelineLogStorage);
    }

    @Test
    void readTaskRunLogRejectsInvalidLogRefPrefix() {
        Dag dag = dag();
        JobRun run = jobRun(DAG_ID);
        TaskRun taskRun = taskRun("spark_node", TaskRunStatus.SUCCEEDED);
        taskRun.setLogRef("22222222-2222-2222-2222-222222222222/" + RUN_ID + "/spark_node/latest.log");
        when(dagRepo.findByIdAndTenantId(DAG_ID, TENANT_ID)).thenReturn(Optional.of(dag));
        when(runRepo.findByIdAndDagIdIn(eq(RUN_ID), argThat(ids -> ids != null && ids.contains(DAG_ID))))
                .thenReturn(Optional.of(run));
        when(taskRunRepo.findByJobRunIdAndTaskKey(RUN_ID, "spark_node")).thenReturn(Optional.of(taskRun));

        assertThatThrownBy(() -> service.readTaskRunLog(DAG_ID, RUN_ID, "spark_node", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("节点日志引用非法");

        verifyNoInteractions(pipelineLogStorage);
    }

    @Test
    void readTaskRunLogReturnsTailLines() throws Exception {
        Dag dag = dag();
        JobRun run = jobRun(DAG_ID);
        TaskRun taskRun = taskRun("spark_node", TaskRunStatus.SUCCEEDED);
        String key = TENANT_ID + "/" + RUN_ID + "/spark_node/latest.log";
        taskRun.setLogRef(key);
        when(dagRepo.findByIdAndTenantId(DAG_ID, TENANT_ID)).thenReturn(Optional.of(dag));
        when(runRepo.findByIdAndDagIdIn(eq(RUN_ID), argThat(ids -> ids != null && ids.contains(DAG_ID))))
                .thenReturn(Optional.of(run));
        when(taskRunRepo.findByJobRunIdAndTaskKey(RUN_ID, "spark_node")).thenReturn(Optional.of(taskRun));
        when(pipelineLogStorage.open(key)).thenReturn(new ByteArrayInputStream(
                "line-1\nline-2\nline-3\n".getBytes(StandardCharsets.UTF_8)));

        OrchestrationService.TaskRunLogResource resource =
                service.readTaskRunLog(DAG_ID, RUN_ID, "spark_node", 2);

        assertThat(resource.objectKey()).isEqualTo(key);
        assertThat(resource.filename()).isEqualTo("spark_node.log");
        assertThat(new String(resource.content().readAllBytes(), StandardCharsets.UTF_8))
                .isEqualTo("line-2\nline-3\n");
        assertThat(resource.contentLength()).isEqualTo("line-2\nline-3\n".getBytes(StandardCharsets.UTF_8).length);
        verify(pipelineLogStorage, never()).size(anyString());
    }

    @Test
    void readTaskRunLogRejectsTooLargeTail() {
        assertThatThrownBy(() -> service.readTaskRunLog(DAG_ID, RUN_ID, "spark_node", 10_001))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("tail 不能超过");

        verifyNoInteractions(dagRepo, runRepo, taskRunRepo, pipelineLogStorage);
    }

    @Test
    void readTaskRunLogRejectsDagOutsideTenant() {
        UUID otherTenant = UUID.fromString("99999999-9999-9999-9999-999999999999");
        TenantContext.setTenantId(otherTenant);
        when(dagRepo.findByIdAndTenantId(DAG_ID, otherTenant)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.readTaskRunLog(DAG_ID, RUN_ID, "spark_node", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("DAG 不存在");

        verifyNoInteractions(runRepo, taskRunRepo, pipelineLogStorage);
    }

    @Test
    void terminalRunSyncKeepsCallbackTerminalRowsAndCompensatesPendingRows() {
        ReflectionTestUtils.setField(service, "pipelineExecutionMode", "GRAPH");
        Dag dag = dag();
        JobRun run = jobRun(dag.getId());
        run.setStatus(DagStatus.FAILED);
        run.setFinishedAt(Instant.parse("2026-07-09T01:03:00Z"));
        TaskRun callbackTerminal = taskRun("extract", TaskRunStatus.SUCCEEDED);
        callbackTerminal.setFinishedAt(Instant.parse("2026-07-09T01:01:00Z"));
        TaskRun pending = taskRun("load", TaskRunStatus.QUEUED);
        PageRequest pageable = PageRequest.of(0, 20);
        when(dagRepo.findByTenantId(TENANT_ID)).thenReturn(List.of(dag));
        when(runRepo.findByDagIdInOrderByStartedAtDesc(
                argThat(ids -> ids != null && ids.contains(DAG_ID)),
                eq(pageable)
        )).thenReturn(new PageImpl<>(List.of(run), pageable, 1));
        when(taskRunRepo.findByJobRunIdForUpdate(RUN_ID)).thenReturn(List.of(callbackTerminal, pending));

        service.listRuns(pageable);

        assertThat(callbackTerminal.getStatus()).isEqualTo(TaskRunStatus.SUCCEEDED);
        assertThat(callbackTerminal.getFinishedAt()).isEqualTo(Instant.parse("2026-07-09T01:01:00Z"));
        assertThat(pending.getStatus()).isEqualTo(TaskRunStatus.FAILED);
        assertThat(pending.getFinishedAt()).isEqualTo(run.getFinishedAt());
        assertThat(pending.getErrorMsg()).isEqualTo("Dagster graph run reported FAILED before node callback completed");
        verify(taskRunRepo, never()).save(callbackTerminal);
        verify(taskRunRepo).save(pending);
    }

    @Test
    void successfulGraphRunPropagatesSkippedCallbackToUnexecutedDownstream() {
        ReflectionTestUtils.setField(service, "pipelineExecutionMode", "GRAPH");
        Dag dag = dag();
        JobRun run = jobRun(dag.getId());
        run.setStatus(DagStatus.SUCCEEDED);
        run.setFinishedAt(Instant.parse("2026-07-12T13:31:27Z"));
        TaskRun sensor = taskRun("sensor", TaskRunStatus.SKIPPED);
        sensor.setFinishedAt(Instant.parse("2026-07-12T13:31:26Z"));
        TaskRun downstream = taskRun("downstream", TaskRunStatus.QUEUED);
        PageRequest pageable = PageRequest.of(0, 20);
        when(dagRepo.findByTenantId(TENANT_ID)).thenReturn(List.of(dag));
        when(runRepo.findByDagIdInOrderByStartedAtDesc(
                argThat(ids -> ids != null && ids.contains(DAG_ID)), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(run), pageable, 1));
        when(taskRunRepo.findByJobRunIdForUpdate(RUN_ID)).thenReturn(List.of(sensor, downstream));
        when(pipelineTaskEdgeRepo.findByDagId(DAG_ID))
                .thenReturn(List.of(pipelineEdge("sensor", "downstream")));

        service.listRuns(pageable);

        assertThat(sensor.getStatus()).isEqualTo(TaskRunStatus.SKIPPED);
        assertThat(downstream.getStatus()).isEqualTo(TaskRunStatus.SKIPPED);
        assertThat(downstream.getStartedAt()).isNull();
        assertThat(downstream.getFinishedAt()).isEqualTo(run.getFinishedAt());
        assertThat(downstream.getErrorMsg()).isEqualTo("Upstream task skipped: sensor");
        verify(taskRunRepo).save(downstream);
    }

    private Dag dag() {
        Dag dag = new Dag();
        dag.setId(DAG_ID);
        dag.setTenantId(TENANT_ID);
        dag.setName("old_pipeline");
        dag.setDagsterJob("old_job");
        dag.setDefinition("{\"nodes\":[]}");
        dag.setEnabled(true);
        dag.setVersion(2);
        return dag;
    }

    private JobRun jobRun(UUID dagId) {
        JobRun run = new JobRun();
        run.setId(RUN_ID);
        run.setDagId(dagId);
        run.setDagsterRunId("dagster-run-1");
        run.setTriggerType(TriggerType.MANUAL);
        run.setStatus(DagStatus.SUCCEEDED);
        run.setStartedAt(Instant.parse("2026-06-23T01:02:03Z"));
        run.setFinishedAt(Instant.parse("2026-06-23T01:03:04Z"));
        return run;
    }

    private PipelineTask pipelineTask(String key) {
        PipelineTask task = new PipelineTask();
        task.setId(UUID.randomUUID());
        task.setTenantId(TENANT_ID);
        task.setDagId(DAG_ID);
        task.setTaskKey(key);
        task.setName(key);
        task.setTaskType(TaskType.SPARK_SQL);
        task.setEngine("SPARK_SQL");
        task.setTargetFqn("onelake.dwd." + key);
        task.setExecutable(true);
        task.setConfig("{\"sql\":\"SELECT 1\"}");
        return task;
    }

    private PipelineCompileResult pipelinePlan(PipelineTask... tasks) {
        return new PipelineCompileResult(
                DAG_ID,
                "pipeline_" + DAG_ID,
                TENANT_ID,
                List.of(tasks).stream()
                        .map(task -> new PipelineCompileResult.TaskCompileResult(
                                task.getId(), task.getTaskKey(), task.getTaskType().name(),
                                true, task.getTargetFqn(), null))
                        .toList(),
                true,
                List.of());
    }

    private PipelineTaskEdge pipelineEdge(String sourceKey, String targetKey) {
        PipelineTaskEdge edge = new PipelineTaskEdge();
        edge.setSourceKey(sourceKey);
        edge.setTargetKey(targetKey);
        edge.setEdgeLayer(EdgeLayer.PIPELINE);
        return edge;
    }

    private TaskRun taskRun(String taskKey, TaskRunStatus status) {
        TaskRun taskRun = new TaskRun();
        taskRun.setId(UUID.randomUUID());
        taskRun.setTenantId(TENANT_ID);
        taskRun.setJobRunId(RUN_ID);
        taskRun.setTaskKey(taskKey);
        taskRun.setStatus(status);
        return taskRun;
    }

    private List<DagStatus> captureRunStatuses() {
        List<DagStatus> statuses = new ArrayList<>();
        doAnswer(invocation -> {
            JobRun run = invocation.getArgument(0);
            if (run.getId() == null) {
                run.setId(RUN_ID);
            }
            statuses.add(run.getStatus());
            return run;
        }).when(runRepo).save(any(JobRun.class));
        return statuses;
    }
}
