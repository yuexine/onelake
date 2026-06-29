package com.onelake.orchestration.service;

import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.outbox.OutboxPublisher;
import com.onelake.orchestration.client.DagsterClient;
import com.onelake.orchestration.domain.entity.Dag;
import com.onelake.orchestration.domain.entity.JobRun;
import com.onelake.orchestration.domain.entity.PipelineTask;
import com.onelake.orchestration.domain.entity.PipelineTaskEdge;
import com.onelake.orchestration.domain.entity.TaskRun;
import com.onelake.orchestration.domain.enums.DagStatus;
import com.onelake.orchestration.domain.enums.EdgeLayer;
import com.onelake.orchestration.domain.enums.TaskRunStatus;
import com.onelake.orchestration.domain.enums.TaskType;
import com.onelake.orchestration.domain.enums.TriggerType;
import com.onelake.orchestration.dto.PipelineCompileResult;
import com.onelake.orchestration.dto.PipelineCompileResult.TaskCompileResult;
import com.onelake.orchestration.repository.DagRepository;
import com.onelake.orchestration.repository.JobRunRepository;
import com.onelake.orchestration.repository.PipelineTaskEdgeRepository;
import com.onelake.orchestration.repository.PipelineTaskRepository;
import com.onelake.orchestration.repository.TaskRunRepository;
import com.onelake.orchestration.service.spi.DagsterRunConfig;
import com.onelake.orchestration.service.spi.SparkRunConfigBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1 tests for {@link OrchestrationService#triggerPipelineRun}.
 *
 * <p>Verifies the new pipeline v2 trigger path:
 * <ul>
 *   <li>Compiles pipeline before launching.</li>
 *   <li>Creates one TaskRun per observable task and initializes status by topology.</li>
 *   <li>Launches Dagster {@code onelake_pipeline_run}.</li>
 *   <li>Publishes {@code pipeline.run.failed} Outbox event on launch failure (C4).</li>
 *   <li><b>Never writes modeling.* schema</b> (cross-schema direct write removed in new path).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class OrchestrationPipelineTriggerTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID DAG_ID = UUID.randomUUID();

    @Mock private DagRepository dagRepo;
    @Mock private JobRunRepository runRepo;
    @Mock private DagsterClient dagster;
    @Mock private JdbcTemplate jdbc;
    @Mock private RuntimeContractService runtimeContractService;
    @Mock private PipelineCompileService compileService;
    @Mock private PipelineTaskRepository taskRepo;
    @Mock private PipelineTaskEdgeRepository edgeRepo;
    @Mock private TaskRunRepository taskRunRepo;
    @Mock private SparkRunConfigBuilder sparkBuilder;
    @Mock private ObjectProvider<OutboxPublisher> outboxProvider;
    @Mock private OutboxPublisher outboxPublisher;

    private OrchestrationService service;

    @BeforeEach
    void setup() {
        service = new OrchestrationService(dagRepo, runRepo, dagster, jdbc,
                runtimeContractService, compileService, taskRepo, edgeRepo, taskRunRepo,
                sparkBuilder, outboxProvider);
        TenantContext.setTenantId(TENANT_ID);
        TenantContext.setUserId(UUID.randomUUID());

        // Simulate DB-generated UUID on save (JobRun/TaskRun.id are DB-generated in production).
        // lenient() because not every test reaches the save point (e.g. early validation failures).
        org.mockito.Mockito.lenient().doAnswer(inv -> {
            JobRun r = inv.getArgument(0);
            if (r.getId() == null) r.setId(UUID.randomUUID());
            return r;
        }).when(runRepo).save(any(JobRun.class));
        org.mockito.Mockito.lenient().doAnswer(inv -> {
            TaskRun t = inv.getArgument(0);
            if (t.getId() == null) t.setId(UUID.randomUUID());
            return t;
        }).when(taskRunRepo).save(any(TaskRun.class));
        org.mockito.Mockito.lenient().when(sparkBuilder.build(any(), anyList()))
                .thenReturn(new DagsterRunConfig("onelake_pipeline_run", Map.of()));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    @SuppressWarnings("unchecked")
    void launchesPipelineAndCreatesTaskRunsPerObservableTask() {
        Dag dag = pipelineDag();
        when(dagRepo.findByIdAndTenantId(DAG_ID, TENANT_ID)).thenReturn(Optional.of(dag));
        when(compileService.compile(DAG_ID)).thenReturn(validPlan("t1", "t2", "t3"));
        PipelineTask t1 = task("t1", true);
        PipelineTask t2 = task("t2", true);
        PipelineTask t3 = task("t3", false); // observation-only node
        when(taskRepo.findByDagIdOrderByCreatedAtAsc(DAG_ID)).thenReturn(List.of(t1, t2, t3));
        when(dagster.launch(anyString(), anyString(), anyString(), any(), anyList()))
                .thenReturn("dagster-run-abc");

        UUID runId = service.triggerPipelineRun(DAG_ID, TriggerType.MANUAL);

        assertThat(runId).isNotNull();

        // JobRun saved twice (create + update with dagsterRunId)
        ArgumentCaptor<JobRun> runCaptor = ArgumentCaptor.forClass(JobRun.class);
        verify(runRepo, org.mockito.Mockito.atLeastOnce()).save(runCaptor.capture());
        JobRun saved = runCaptor.getValue();
        assertThat(saved.getDagId()).isEqualTo(DAG_ID);
        assertThat(saved.getDagsterRunId()).isEqualTo("dagster-run-abc");

        // TaskRun is created for every valid task so the UI can observe all nodes.
        // Dagster runConfig is still built only from real executable engine tasks.
        ArgumentCaptor<TaskRun> taskRunCaptor = ArgumentCaptor.forClass(TaskRun.class);
        verify(taskRunRepo, org.mockito.Mockito.atLeast(3)).save(taskRunCaptor.capture());
        List<TaskRun> taskRuns = taskRunCaptor.getAllValues();
        assertThat(taskRuns).extracting(TaskRun::getTaskKey)
                .containsExactlyInAnyOrder("t1", "t2", "t3");
        assertThat(taskRuns).allSatisfy(tr ->
                assertThat(tr.getStatus()).isEqualTo(TaskRunStatus.RUNNING));

        // C4 verified: NO modeling.* writes through JdbcTemplate
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        verify(jdbc, never()).update(anyString(), any(Object.class));
    }

    @Test
    void triggerInitializesTaskRunsByDagTopologyAndFanOut() {
        Dag dag = pipelineDag();
        when(dagRepo.findByIdAndTenantId(DAG_ID, TENANT_ID)).thenReturn(Optional.of(dag));
        when(compileService.compile(DAG_ID))
                .thenReturn(validPlan("sync_user", "spark_a", "spark_b", "quality_gate"));
        PipelineTask sync = task("sync_user", false);
        sync.setTaskType(TaskType.SYNC_REF);
        sync.setTargetFqn("onelake.ods.user");
        PipelineTask sparkA = task("spark_a", true);
        sparkA.setTaskType(TaskType.SPARK_SQL);
        sparkA.setEngine("SPARK_SQL");
        sparkA.setTargetFqn("onelake.dwd.user_a");
        sparkA.setConfig("{\"sql\":\"select * from onelake.ods.user\"}");
        PipelineTask sparkB = task("spark_b", true);
        sparkB.setTaskType(TaskType.SPARK_SQL);
        sparkB.setEngine("SPARK_SQL");
        sparkB.setTargetFqn("onelake.dwd.user_b");
        sparkB.setConfig("{\"sql\":\"select * from onelake.ods.user\"}");
        PipelineTask gate = task("quality_gate", false);
        gate.setTaskType(TaskType.QUALITY_GATE);
        gate.setConfig("{\"rules\":[{\"type\":\"not_null\",\"column\":\"user_id\"}]}");
        when(taskRepo.findByDagIdOrderByCreatedAtAsc(DAG_ID))
                .thenReturn(List.of(sync, sparkA, sparkB, gate));
        when(edgeRepo.findByDagId(DAG_ID)).thenReturn(List.of(
                edge("sync_user", "spark_a"),
                edge("sync_user", "spark_b"),
                edge("spark_a", "quality_gate"),
                edge("spark_b", "quality_gate")));
        when(dagster.launch(anyString(), anyString(), anyString(), any(), anyList()))
                .thenReturn("dagster-run-topology");

        service.triggerPipelineRun(DAG_ID, TriggerType.MANUAL);

        ArgumentCaptor<TaskRun> taskRunCaptor = ArgumentCaptor.forClass(TaskRun.class);
        verify(taskRunRepo, org.mockito.Mockito.atLeast(4)).save(taskRunCaptor.capture());
        Map<String, TaskRunStatus> statusByKey = taskRunCaptor.getAllValues().stream()
                .collect(java.util.stream.Collectors.toMap(
                        TaskRun::getTaskKey,
                        TaskRun::getStatus,
                        (oldValue, newValue) -> newValue));
        assertThat(statusByKey).containsEntry("sync_user", TaskRunStatus.SUCCEEDED);
        assertThat(statusByKey).containsEntry("spark_a", TaskRunStatus.RUNNING);
        assertThat(statusByKey).containsEntry("spark_b", TaskRunStatus.RUNNING);
        assertThat(statusByKey).containsEntry("quality_gate", TaskRunStatus.QUEUED);
    }

    @Test
    void rejectsWhenCompileFails() {
        Dag dag = pipelineDag();
        when(dagRepo.findByIdAndTenantId(DAG_ID, TENANT_ID)).thenReturn(Optional.of(dag));
        TaskCompileResult bad = new TaskCompileResult(
                UUID.randomUUID(), "t_bad", "SPARK_SQL", false, null, "missing config.sql");
        when(compileService.compile(DAG_ID)).thenReturn(new PipelineCompileResult(
                DAG_ID, "pipeline_" + DAG_ID, TENANT_ID, List.of(bad), false, List.of()));

        assertThatThrownBy(() -> service.triggerPipelineRun(DAG_ID, TriggerType.MANUAL))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("编译未通过");

        verify(dagster, never()).launch(anyString(), anyString(), anyString(), any(), anyList());
    }

    @Test
    void rejectsWhenNoExecutableTasks() {
        Dag dag = pipelineDag();
        when(dagRepo.findByIdAndTenantId(DAG_ID, TENANT_ID)).thenReturn(Optional.of(dag));
        when(compileService.compile(DAG_ID)).thenReturn(validPlan());
        when(taskRepo.findByDagIdOrderByCreatedAtAsc(DAG_ID))
                .thenReturn(List.of(task("spark_only", false)));

        assertThatThrownBy(() -> service.triggerPipelineRun(DAG_ID, TriggerType.MANUAL))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("可执行任务");
    }

    @Test
    void publishesFailedEventWhenDagsterLaunchFails() {
        Dag dag = pipelineDag();
        when(dagRepo.findByIdAndTenantId(DAG_ID, TENANT_ID)).thenReturn(Optional.of(dag));
        when(compileService.compile(DAG_ID)).thenReturn(validPlan());
        when(taskRepo.findByDagIdOrderByCreatedAtAsc(DAG_ID))
                .thenReturn(List.of(task("t1", true)));
        when(dagster.launch(anyString(), anyString(), anyString(), any(), anyList()))
                .thenThrow(new RuntimeException("dagster unavailable"));
        when(outboxProvider.getIfAvailable()).thenReturn(outboxPublisher);
        when(taskRunRepo.findByJobRunId(any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.triggerPipelineRun(DAG_ID, TriggerType.MANUAL))
                .isInstanceOf(BizException.class);

        // C4: pipeline.run.failed Outbox event published
        ArgumentCaptor<String> typeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(outboxPublisher).publish(typeCaptor.capture(), anyString(), payloadCaptor.capture());
        assertThat(typeCaptor.getValue()).isEqualTo("pipeline.run.failed");
        assertThat(payloadCaptor.getValue().get("errorMsg")).asString().contains("dagster unavailable");
    }

    @Test
    @SuppressWarnings("unchecked")
    void refreshTerminalPipelineRunUpdatesTaskRunsAndPublishesTaskLoadedPayload() {
        Dag dag = pipelineDag();
        UUID runId = UUID.randomUUID();
        JobRun run = new JobRun();
        run.setId(runId);
        run.setDagId(DAG_ID);
        run.setDagsterRunId("dagster-run-success");
        run.setTriggerType(TriggerType.MANUAL);
        run.setStatus(DagStatus.RUNNING);
        run.setStartedAt(Instant.parse("2026-06-25T01:00:00Z"));

        PipelineTask task = task("t1", true);
        task.setTaskType(TaskType.PYSPARK);
        task.setName("Spark 用户字段治理");
        task.setEngine("PYSPARK");
        task.setTargetFqn("iceberg.dwd.orders");
        task.setModelId(UUID.randomUUID());
        task.setConfig("""
            {
              "from_tables": ["onelake.ods.orders"],
              "catalog": {
                "description": "Spark 治理结果表",
                "tags": ["governed"],
                "columns": [
                  {"name": "phone", "type": "VARCHAR", "classification": "L3"}
                ]
              }
            }
            """);

        TaskRun taskRun = new TaskRun();
        taskRun.setId(UUID.randomUUID());
        taskRun.setTenantId(TENANT_ID);
        taskRun.setJobRunId(runId);
        taskRun.setTaskKey("t1");
        taskRun.setStatus(TaskRunStatus.QUEUED);

        when(dagRepo.findByTenantId(TENANT_ID)).thenReturn(List.of(dag));
        when(dagRepo.findById(DAG_ID)).thenReturn(Optional.of(dag));
        when(runRepo.findByDagIdInOrderByStartedAtDesc(any(), any()))
                .thenReturn(new PageImpl<>(List.of(run), PageRequest.of(0, 10), 1));
        when(dagster.getRunStatus("dagster-run-success"))
                .thenReturn(new DagsterClient.RunStatus(
                        "dagster-run-success",
                        "SUCCESS",
                        Instant.parse("2026-06-25T01:00:00Z"),
                        Instant.parse("2026-06-25T01:01:00Z")));
        when(taskRunRepo.findByJobRunId(runId)).thenReturn(List.of(taskRun));
        when(taskRepo.findByDagIdAndTaskKey(DAG_ID, "t1")).thenReturn(Optional.of(task));
        when(outboxProvider.getIfAvailable()).thenReturn(outboxPublisher);
        when(jdbc.query(anyString(), any(PreparedStatementSetter.class), any(ResultSetExtractor.class)))
                .thenReturn(42L);

        service.listRuns(PageRequest.of(0, 10));

        assertThat(taskRun.getStatus()).isEqualTo(TaskRunStatus.SUCCEEDED);
        assertThat(taskRun.getFinishedAt()).isEqualTo(Instant.parse("2026-06-25T01:01:00Z"));
        assertThat(taskRun.getRowsWritten()).isEqualTo(42L);
        assertThat(taskRun.getArtifactPath()).isEqualTo("table:dwd.orders");

        ArgumentCaptor<String> typeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(outboxPublisher, org.mockito.Mockito.atLeastOnce())
                .publish(typeCaptor.capture(), anyString(), payloadCaptor.capture());

        int taskLoadedIndex = typeCaptor.getAllValues().indexOf("pipeline.task.loaded");
        assertThat(taskLoadedIndex).isGreaterThanOrEqualTo(0);
        Map<String, Object> payload = payloadCaptor.getAllValues().get(taskLoadedIndex);
        assertThat(payload).containsEntry("targetFqn", "iceberg.dwd.orders");
        assertThat(payload).containsEntry("modelId", task.getModelId().toString());
        assertThat(payload).containsEntry("taskType", "PYSPARK");
        assertThat(payload).containsEntry("taskName", "Spark 用户字段治理");
        assertThat(payload).containsEntry("engine", "PYSPARK");
        assertThat(payload).containsEntry("rowsWritten", 42L);
        assertThat(payload).containsEntry("artifactPath", "table:dwd.orders");
        assertThat(payload.get("fromTables")).asList().containsExactly("onelake.ods.orders");
        assertThat(payload.get("catalog")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("description", "Spark 治理结果表");
    }

    @Test
    void terminalRunDoesNotPublishLoadedEventForObservationOnlyTask() {
        Dag dag = pipelineDag();
        UUID runId = UUID.randomUUID();
        JobRun run = new JobRun();
        run.setId(runId);
        run.setDagId(DAG_ID);
        run.setDagsterRunId("dagster-run-success");
        run.setTriggerType(TriggerType.MANUAL);
        run.setStatus(DagStatus.RUNNING);
        run.setStartedAt(Instant.parse("2026-06-25T01:00:00Z"));

        PipelineTask task = task("sync_ref", false);
        task.setTaskType(TaskType.SYNC_REF);
        task.setTargetFqn("iceberg.ods.user");

        TaskRun taskRun = new TaskRun();
        taskRun.setId(UUID.randomUUID());
        taskRun.setTenantId(TENANT_ID);
        taskRun.setJobRunId(runId);
        taskRun.setTaskKey("sync_ref");
        taskRun.setStatus(TaskRunStatus.QUEUED);

        when(dagRepo.findByTenantId(TENANT_ID)).thenReturn(List.of(dag));
        when(dagRepo.findById(DAG_ID)).thenReturn(Optional.of(dag));
        when(runRepo.findByDagIdInOrderByStartedAtDesc(any(), any()))
                .thenReturn(new PageImpl<>(List.of(run), PageRequest.of(0, 10), 1));
        when(dagster.getRunStatus("dagster-run-success"))
                .thenReturn(new DagsterClient.RunStatus(
                        "dagster-run-success",
                        "SUCCESS",
                        Instant.parse("2026-06-25T01:00:00Z"),
                        Instant.parse("2026-06-25T01:01:00Z")));
        when(taskRunRepo.findByJobRunId(runId)).thenReturn(List.of(taskRun));
        when(taskRepo.findByDagIdAndTaskKey(DAG_ID, "sync_ref")).thenReturn(Optional.of(task));
        when(outboxProvider.getIfAvailable()).thenReturn(outboxPublisher);

        service.listRuns(PageRequest.of(0, 10));

        ArgumentCaptor<String> typeCaptor = ArgumentCaptor.forClass(String.class);
        verify(outboxPublisher, org.mockito.Mockito.atLeastOnce())
                .publish(typeCaptor.capture(), anyString(), any());
        assertThat(typeCaptor.getAllValues()).doesNotContain("pipeline.task.loaded");
        assertThat(typeCaptor.getAllValues()).contains("pipeline.run.succeeded");
    }

    @Test
    void terminalFailedRunPropagatesUpstreamFailedToDownstreamQueuedTasks() {
        Dag dag = pipelineDag();
        UUID runId = UUID.randomUUID();
        JobRun run = new JobRun();
        run.setId(runId);
        run.setDagId(DAG_ID);
        run.setDagsterRunId("dagster-run-failed");
        run.setTriggerType(TriggerType.MANUAL);
        run.setStatus(DagStatus.RUNNING);
        run.setStartedAt(Instant.parse("2026-06-25T01:00:00Z"));

        TaskRun upstream = new TaskRun();
        upstream.setId(UUID.randomUUID());
        upstream.setTenantId(TENANT_ID);
        upstream.setJobRunId(runId);
        upstream.setTaskKey("t_upstream");
        upstream.setStatus(TaskRunStatus.FAILED);

        TaskRun downstream = new TaskRun();
        downstream.setId(UUID.randomUUID());
        downstream.setTenantId(TENANT_ID);
        downstream.setJobRunId(runId);
        downstream.setTaskKey("t_downstream");
        downstream.setStatus(TaskRunStatus.QUEUED);

        PipelineTask upstreamTask = task("t_upstream", true);
        PipelineTask downstreamTask = task("t_downstream", true);

        when(dagRepo.findByTenantId(TENANT_ID)).thenReturn(List.of(dag));
        when(dagRepo.findById(DAG_ID)).thenReturn(Optional.of(dag));
        when(runRepo.findByDagIdInOrderByStartedAtDesc(any(), any()))
                .thenReturn(new PageImpl<>(List.of(run), PageRequest.of(0, 10), 1));
        when(dagster.getRunStatus("dagster-run-failed"))
                .thenReturn(new DagsterClient.RunStatus(
                        "dagster-run-failed",
                        "FAILURE",
                        Instant.parse("2026-06-25T01:00:00Z"),
                        Instant.parse("2026-06-25T01:01:00Z")));
        when(taskRunRepo.findByJobRunId(runId)).thenReturn(List.of(upstream, downstream));
        when(taskRepo.findByDagIdAndTaskKey(DAG_ID, "t_upstream")).thenReturn(Optional.of(upstreamTask));
        when(taskRepo.findByDagIdAndTaskKey(DAG_ID, "t_downstream")).thenReturn(Optional.of(downstreamTask));
        when(edgeRepo.findByDagId(DAG_ID)).thenReturn(List.of(edge("t_upstream", "t_downstream")));

        service.listRuns(PageRequest.of(0, 10));

        assertThat(upstream.getStatus()).isEqualTo(TaskRunStatus.FAILED);
        assertThat(downstream.getStatus()).isEqualTo(TaskRunStatus.UPSTREAM_FAILED);
        assertThat(downstream.getErrorMsg()).contains("t_upstream");
    }

    @Test
    void rejectsWhenTenantContextMissing() {
        TenantContext.clear();
        assertThatThrownBy(() -> service.triggerPipelineRun(DAG_ID, TriggerType.MANUAL))
                .isInstanceOf(BizException.class);
        TenantContext.setTenantId(TENANT_ID);
    }

    @Test
    void rejectsWhenPipelineNotFound() {
        when(dagRepo.findByIdAndTenantId(any(), any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.triggerPipelineRun(DAG_ID, TriggerType.MANUAL))
                .isInstanceOf(BizException.class);
    }

    // ---------- helpers ----------

    private Dag pipelineDag() {
        Dag d = new Dag();
        d.setId(DAG_ID);
        d.setTenantId(TENANT_ID);
        d.setName("test_pipeline");
        d.setDagsterJob("onelake_pipeline_run");
        d.setDefinition("{}");
        d.setEnabled(true);
        d.setResourceGroup("spark-default");
        d.setComputeProfile("spark-small");
        return d;
    }

    private PipelineCompileResult validPlan() {
        return validPlan("t1");
    }

    private PipelineCompileResult validPlan(String... taskKeys) {
        List<TaskCompileResult> tasks = java.util.Arrays.stream(taskKeys)
                .map(key -> new TaskCompileResult(
                        UUID.randomUUID(), key, "SPARK_SQL",
                        true, "iceberg.dwd.orders", null))
                .toList();
        return new PipelineCompileResult(
                DAG_ID, "pipeline_" + DAG_ID, TENANT_ID,
                tasks, true, List.of());
    }

    private PipelineTask task(String key, boolean executable) {
        PipelineTask t = new PipelineTask();
        t.setId(UUID.randomUUID());
        t.setTenantId(TENANT_ID);
        t.setDagId(DAG_ID);
        t.setTaskKey(key);
        t.setName(key);
        t.setTaskType(TaskType.SPARK_SQL);
        t.setEngine("SPARK_SQL");
        t.setTargetFqn("iceberg.dwd." + key);
        t.setConfig("{\"sql\":\"SELECT 1\"}");
        t.setExecutable(executable);
        return t;
    }

    private PipelineTaskEdge edge(String sourceKey, String targetKey) {
        PipelineTaskEdge edge = new PipelineTaskEdge();
        edge.setSourceKey(sourceKey);
        edge.setTargetKey(targetKey);
        edge.setEdgeLayer(EdgeLayer.PIPELINE);
        return edge;
    }
}
