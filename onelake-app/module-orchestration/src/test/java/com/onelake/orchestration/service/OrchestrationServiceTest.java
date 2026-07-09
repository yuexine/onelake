package com.onelake.orchestration.service;

import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.orchestration.client.DagsterClient;
import com.onelake.orchestration.domain.entity.Dag;
import com.onelake.orchestration.domain.entity.JobRun;
import com.onelake.orchestration.domain.entity.PipelineTask;
import com.onelake.orchestration.domain.entity.PipelineTaskEdge;
import com.onelake.orchestration.domain.enums.DagStatus;
import com.onelake.orchestration.domain.enums.EdgeLayer;
import com.onelake.orchestration.domain.enums.TaskType;
import com.onelake.orchestration.domain.enums.TriggerType;
import com.onelake.orchestration.dto.DagDTO;
import com.onelake.orchestration.dto.JobRunDTO;
import com.onelake.orchestration.dto.PipelineCompileResult;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

// Pipeline dependencies used by the unified Spark runtime path.
import com.onelake.common.outbox.OutboxPublisher;
import com.onelake.orchestration.repository.PipelineTaskEdgeRepository;
import com.onelake.orchestration.repository.PipelineTaskRepository;
import com.onelake.orchestration.repository.TaskRunRepository;
import com.onelake.orchestration.service.spi.DagsterRunConfig;
import com.onelake.orchestration.service.spi.SparkRunConfigBuilder;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
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

    // Pipeline dependencies shared by orchestration runtime tests.
    @Mock private PipelineCompileService pipelineCompileService;
    @Mock private PipelineTaskRepository pipelineTaskRepo;
    @Mock private PipelineTaskEdgeRepository pipelineTaskEdgeRepo;
    @Mock private TaskRunRepository taskRunRepo;
    @Mock private SparkRunConfigBuilder sparkBuilder;
    @Mock private ObjectProvider<OutboxPublisher> outboxPublisher;

    private OrchestrationService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT_ID);
        lenient().when(runtimeContractService.triggerBlockedReason(anyString(), anyMap())).thenReturn(Optional.empty());
        lenient().when(runtimeContractService.launchBlockedReason(anyString(), anyMap())).thenReturn(Optional.empty());
        service = new OrchestrationService(dagRepo, runRepo, dagster, jdbc,
            runtimeContractService, pipelineCompileService, pipelineTaskRepo, pipelineTaskEdgeRepo, taskRunRepo,
            sparkBuilder, outboxPublisher);
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
        PageRequest pageable = PageRequest.of(0, 20);
        when(dagRepo.findByTenantId(TENANT_ID)).thenReturn(List.of(dag));
        when(runRepo.findByDagIdInOrderByStartedAtDesc(
            argThat(ids -> ids != null && ids.size() == 1 && ids.contains(DAG_ID)),
            eq(pageable)
        )).thenReturn(new PageImpl<>(List.of(run), pageable, 1));

        Page<JobRunDTO> page = service.listRuns(pageable);

        assertThat(page.getTotalElements()).isEqualTo(1);
        JobRunDTO dto = page.getContent().get(0);
        assertThat(dto.dagId()).isEqualTo(DAG_ID);
        assertThat(dto.dagName()).isEqualTo("old_pipeline");
        assertThat(dto.dagsterJob()).isEqualTo("old_job");
        assertThat(dto.dagsterRunId()).isEqualTo("dagster-run-1");
        assertThat(dto.status()).isEqualTo("SUCCEEDED");
        assertThat(dto.triggerType()).isEqualTo("MANUAL");
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
        // Runtime status refresh must not write back into modeling-owned tables.
        verify(jdbc, never()).update(startsWith("UPDATE modeling.model_run"), any(), any(), any(), any());
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
        // Runtime status reads stay inside orchestration.
        verify(jdbc, never()).update(startsWith("UPDATE modeling.model_run"), any(), any(), any(), any());
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
    void listDagsIncludesLatestRunMetadata() {
        Dag dag = dag();
        when(dagRepo.findByTenantId(TENANT_ID)).thenReturn(List.of(dag));
        when(runRepo.findFirstByDagIdOrderByStartedAtDesc(DAG_ID)).thenReturn(Optional.of(jobRun(DAG_ID)));

        List<DagDTO> dags = service.listDags();

        assertThat(dags).hasSize(1);
        assertThat(dags.get(0).lastRun()).isNotNull();
        assertThat(dags.get(0).lastRun().dagsterRunId()).isEqualTo("dagster-run-1");
        assertThat(dags.get(0).lastRun().status()).isEqualTo("SUCCEEDED");
        assertThat(dags.get(0).lastRun().dagName()).isEqualTo("old_pipeline");
        assertThat(dags.get(0).triggerable()).isTrue();
        assertThat(dags.get(0).triggerBlockedReason()).isNull();
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
        when(sparkBuilder.build(any(), anyList()))
                .thenReturn(new DagsterRunConfig("onelake_pipeline_run", Map.of("ops", Map.of())));
        when(dagster.launch(eq("onelake_pipeline_run"), eq("onelake"), eq("onelake-loc"), anyMap(), anyList()))
                .thenReturn("dagster-legacy");

        UUID runId = service.triggerPipelineRun(DAG_ID, TriggerType.MANUAL);

        assertThat(runId).isEqualTo(RUN_ID);
        assertThat(statuses).contains(DagStatus.QUEUED, DagStatus.RUNNING);
        verify(sparkBuilder).build(any(), anyList());
        verify(sparkBuilder, never()).buildGraphRunConfig(any(), anyList(), anyList(), anyString(), anyInt());
        verify(dagster).launch(eq("onelake_pipeline_run"), eq("onelake"), eq("onelake-loc"),
                anyMap(), anyList());
    }

    @Test
    void triggerPipelineRunUsesGraphJobWhenConfigured() {
        List<DagStatus> statuses = captureRunStatuses();
        ReflectionTestUtils.setField(service, "pipelineExecutionMode", "GRAPH");
        ReflectionTestUtils.setField(service, "pipelineCallbackBaseUrl", "http://localhost:8080");
        ReflectionTestUtils.setField(service, "pipelineMaxParallel", 8);
        Dag dag = dag();
        dag.setDagsterJob("onelake_pipeline_run");
        PipelineTask task = pipelineTask("spark_node");
        PipelineTaskEdge edge = pipelineEdge("source", "spark_node");
        when(dagRepo.findByIdAndTenantId(DAG_ID, TENANT_ID)).thenReturn(Optional.of(dag));
        when(pipelineCompileService.compile(DAG_ID)).thenReturn(pipelinePlan(task));
        when(pipelineTaskRepo.findByDagIdOrderByCreatedAtAsc(DAG_ID)).thenReturn(List.of(task));
        when(pipelineTaskEdgeRepo.findByDagId(DAG_ID)).thenReturn(List.of(edge));
        when(sparkBuilder.buildGraphRunConfig(any(), anyList(), anyList(), eq("http://localhost:8080"), eq(8)))
                .thenReturn(new DagsterRunConfig("onelake_pipeline_graph_run", Map.of("ops", Map.of())));
        when(dagster.launch(eq("onelake_pipeline_graph_run"), eq("onelake"), eq("onelake-loc"), anyMap(), anyList()))
                .thenReturn("dagster-graph");

        UUID runId = service.triggerPipelineRun(DAG_ID, TriggerType.MANUAL);

        assertThat(runId).isEqualTo(RUN_ID);
        assertThat(statuses).contains(DagStatus.QUEUED, DagStatus.RUNNING);
        verify(sparkBuilder, never()).build(any(), anyList());
        verify(sparkBuilder).buildGraphRunConfig(any(), anyList(), anyList(), eq("http://localhost:8080"), eq(8));
        verify(dagster).launch(eq("onelake_pipeline_graph_run"), eq("onelake"), eq("onelake-loc"),
                anyMap(), anyList());
    }

    @Test
    void triggerPipelineRunValidatesGraphJobBeforeCreatingRun() {
        ReflectionTestUtils.setField(service, "pipelineExecutionMode", "GRAPH");
        Dag dag = dag();
        dag.setDagsterJob("onelake_pipeline_run");
        when(dagRepo.findByIdAndTenantId(DAG_ID, TENANT_ID)).thenReturn(Optional.of(dag));
        when(runtimeContractService.launchBlockedReason(eq("onelake_pipeline_graph_run"), anyMap()))
                .thenReturn(Optional.of("Dagster repository 未暴露作业: onelake_pipeline_graph_run"));

        assertThatThrownBy(() -> service.triggerPipelineRun(DAG_ID, TriggerType.MANUAL))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("onelake_pipeline_graph_run");

        verify(pipelineCompileService, never()).compile(any());
        verifyNoInteractions(runRepo);
        verifyNoInteractions(dagster);
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

    private PipelineCompileResult pipelinePlan(PipelineTask task) {
        return new PipelineCompileResult(
                DAG_ID,
                "pipeline_" + DAG_ID,
                TENANT_ID,
                List.of(new PipelineCompileResult.TaskCompileResult(
                        task.getId(), task.getTaskKey(), task.getTaskType().name(),
                        true, task.getTargetFqn(), null)),
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
