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
import com.onelake.orchestration.domain.enums.EdgeLayer;
import com.onelake.orchestration.domain.enums.TaskRunStatus;
import com.onelake.orchestration.domain.enums.TaskType;
import com.onelake.orchestration.domain.enums.TriggerType;
import com.onelake.orchestration.dto.OdsDwdTemplateRequest;
import com.onelake.orchestration.dto.OdsDwdTemplateResult;
import com.onelake.orchestration.dto.PipelineTaskRequest;
import com.onelake.orchestration.dto.PipelineValidationResult;
import com.onelake.orchestration.repository.DagRepository;
import com.onelake.orchestration.repository.JobRunRepository;
import com.onelake.orchestration.repository.PipelineTaskEdgeRepository;
import com.onelake.orchestration.repository.PipelineTaskRepository;
import com.onelake.orchestration.repository.TaskRunRepository;
import com.onelake.orchestration.service.spi.SparkRunConfigBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end orchestration test: serializes the full pipeline v2 lifecycle through every
 * service boundary (PipelineService → PipelineCompileService → OrchestrationService →
 * OutboxPublisher), verifying contracts and event flow without a real DB.
 *
 * <p>Tests the happy path that production will exercise:
 * <ol>
 *   <li>Create blank pipeline</li>
 *   <li>Add SYNC_REF + SPARK_SQL tasks</li>
 *   <li>Validate the Spark executable baseline</li>
 *   <li>Transition DRAFT → VALIDATED (validation enforces pass)</li>
 *   <li>Transition VALIDATED → PUBLISHED (emits pipeline.published)</li>
 *   <li>Trigger run (creates TaskRun per task, launches Dagster)</li>
 *   <li>Refresh status (terminal → emits pipeline.run.succeeded + pipeline.task.loaded)</li>
 * </ol>
 *
 * <p>This is the single integration point that catches wiring bugs across services that
 * unit tests on individual services might miss.
 */
@ExtendWith(MockitoExtension.class)
class PipelineEndToEndTest {

    @Mock private DagRepository dagRepo;
    @Mock private PipelineTaskRepository taskRepo;
    @Mock private PipelineTaskEdgeRepository edgeRepo;
    @Mock private TaskRunRepository taskRunRepo;
    @Mock private JobRunRepository runRepo;
    @Mock private DagsterClient dagster;
    @Mock private JdbcTemplate jdbc;
    @Mock private ObjectProvider<OutboxPublisher> outboxProvider;
    @Mock private OutboxPublisher outboxPublisher;
    @Mock private RuntimeContractService runtimeContractService;

    private PipelineService pipelineService;
    private PipelineCompileService compileService;
    private OrchestrationService orchestrationService;

    // Per-test pipeline state
    private UUID tenantId;
    private UUID dagId;
    private UUID modelId;
    private Dag dag;
    private final Map<String, PipelineTask> tasks = new LinkedHashMap<>();
    private final List<PipelineTaskEdge> edges = new java.util.ArrayList<>();
    private final AtomicReference<JobRun> latestRun = new AtomicReference<>();
    private final List<TaskRun> taskRuns = new java.util.ArrayList<>();
    private final List<Map<String, Object>> outboxPayloads = new java.util.ArrayList<>();
    private final List<String> outboxTypes = new java.util.ArrayList<>();

    @BeforeEach
    void setup() {
        compileService = new PipelineCompileService(dagRepo, taskRepo, edgeRepo);
        pipelineService = new PipelineService(dagRepo, taskRepo, edgeRepo, taskRunRepo,
                compileService, outboxProvider);
        orchestrationService = new OrchestrationService(dagRepo, runRepo, dagster, jdbc,
                runtimeContractService, compileService, taskRepo, edgeRepo, taskRunRepo,
                new SparkRunConfigBuilder(), outboxProvider);

        tenantId = UUID.randomUUID();
        dagId = UUID.randomUUID();
        modelId = UUID.randomUUID();
        TenantContext.setTenantId(tenantId);
        TenantContext.setUserId(UUID.randomUUID());

        dag = new Dag();
        dag.setId(dagId);
        dag.setTenantId(tenantId);
        dag.setName("e2e_pipeline");
        dag.setDagsterJob("onelake_pipeline_run");
        dag.setDefinition("{}");
        dag.setEnabled(true);
        dag.setVersion(1);
        dag.setStatus("DRAFT");
        dag.setPipelineKind("BLANK");
        dag.setEngine("SPARK");
        dag.setResourceGroup("spark-default");
        dag.setComputeProfile("spark-small");
        lenient().when(runtimeContractService.launchBlockedReason(anyString(), any()))
                .thenReturn(Optional.empty());

        // Wire repo mocks to operate on in-memory state
        lenient().when(dagRepo.findByIdAndTenantId(eq(dagId), eq(tenantId))).thenReturn(Optional.of(dag));
        lenient().when(dagRepo.findById(eq(dagId))).thenReturn(Optional.of(dag));
        lenient().when(dagRepo.findByTenantId(eq(tenantId))).thenReturn(List.of(dag));
        lenient().when(dagRepo.save(any(Dag.class))).thenAnswer(inv -> {
            Dag d = inv.getArgument(0);
            if (d.getId() == null) d.setId(dagId);
            return d;
        });
        lenient().when(taskRepo.findByDagIdOrderByCreatedAtAsc(eq(dagId)))
                .thenAnswer(inv -> new java.util.ArrayList<>(tasks.values()));
        lenient().when(taskRepo.findByDagIdAndTaskKey(eq(dagId), anyString()))
                .thenAnswer(inv -> {
                    String key = inv.getArgument(1);
                    return Optional.ofNullable(tasks.get(key));
                });
        lenient().when(taskRepo.findByTenantIdAndModelId(eq(tenantId), any())).thenAnswer(inv -> {
            UUID mid = inv.getArgument(1);
            long count = tasks.values().stream().filter(t -> mid.equals(t.getModelId())).count();
            return count;
        });
        lenient().when(taskRepo.save(any(PipelineTask.class))).thenAnswer(inv -> {
            PipelineTask t = inv.getArgument(0);
            if (t.getId() == null) t.setId(UUID.randomUUID());
            tasks.put(t.getTaskKey(), t);
            return t;
        });
        lenient().when(taskRepo.saveAll(anyList())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Iterable<PipelineTask> iter = inv.getArgument(0);
            for (PipelineTask t : iter) {
                if (t.getId() == null) t.setId(UUID.randomUUID());
                tasks.put(t.getTaskKey(), t);
            }
            return inv.getArgument(0);
        });
        lenient().when(edgeRepo.findByDagId(eq(dagId)))
                .thenAnswer(inv -> new java.util.ArrayList<>(edges));
        lenient().when(edgeRepo.save(any(PipelineTaskEdge.class))).thenAnswer(inv -> {
            PipelineTaskEdge e = inv.getArgument(0);
            if (e.getId() == null) e.setId(UUID.randomUUID());
            edges.add(e);
            return e;
        });
        lenient().when(taskRunRepo.findByJobRunId(any())).thenReturn(taskRuns);
        lenient().when(taskRunRepo.save(any(TaskRun.class))).thenAnswer(inv -> {
            TaskRun t = inv.getArgument(0);
            if (t.getId() == null) t.setId(UUID.randomUUID());
            boolean replaced = false;
            for (int i = 0; i < taskRuns.size(); i++) {
                if (taskRuns.get(i).getId().equals(t.getId())) {
                    taskRuns.set(i, t);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                taskRuns.add(t);
            }
            return t;
        });
        lenient().when(runRepo.save(any(JobRun.class))).thenAnswer(inv -> {
            JobRun r = inv.getArgument(0);
            if (r.getId() == null) r.setId(UUID.randomUUID());
            latestRun.set(r);
            return r;
        });
        lenient().when(runRepo.findByDagIdInOrderByStartedAtDesc(any(), any())).thenAnswer(inv -> {
            JobRun r = latestRun.get();
            if (r == null) return org.springframework.data.domain.Page.empty();
            return new org.springframework.data.domain.PageImpl<>(
                    List.of(r), inv.getArgument(1), 1);
        });

        // Outbox capture
        lenient().when(outboxProvider.getIfAvailable()).thenReturn(outboxPublisher);
        lenient().doAnswer(inv -> {
            outboxTypes.add(inv.getArgument(0));
            outboxPayloads.add(inv.getArgument(2));
            return null;
        }).when(outboxPublisher).publish(anyString(), anyString(), any());

        // Run-time contract: v2 path unblocked
        lenient().when(runtimeContractService.triggerBlockedReason(anyString(), any())).thenReturn(Optional.empty());
    }

    @AfterEach
    void teardown() {
        tasks.clear();
        edges.clear();
        taskRuns.clear();
        outboxPayloads.clear();
        outboxTypes.clear();
        TenantContext.clear();
    }

    @Test
    @DisplayName("Full lifecycle: create → add Spark tasks → validate → publish → trigger → terminal → outbox")
    void fullLifecycleHappyPath() {
        // === Step 1: create tasks via service (simulating API calls) ===
        PipelineTask syncTask = new PipelineTask();
        syncTask.setId(UUID.randomUUID());
        syncTask.setTenantId(tenantId);
        syncTask.setDagId(dagId);
        syncTask.setTaskKey("sync_ref_ods");
        syncTask.setTaskType(TaskType.SYNC_REF);
        syncTask.setName("ODS source");
        syncTask.setEngine("SPARK_SQL");
        syncTask.setTargetFqn("iceberg.ods.orders");
        syncTask.setSyncTaskId(UUID.randomUUID());  // structurally valid
        syncTask.setConfig("{}");
        tasks.put("sync_ref_ods", syncTask);

        PipelineTask sparkTask = new PipelineTask();
        sparkTask.setId(UUID.randomUUID());
        sparkTask.setTenantId(tenantId);
        sparkTask.setDagId(dagId);
        sparkTask.setTaskKey("spark_dwd_sink");
        sparkTask.setTaskType(TaskType.SPARK_SQL);
        sparkTask.setName("DWD orders");
        sparkTask.setEngine("SPARK_SQL");
        sparkTask.setTargetFqn("iceberg.dwd.orders");
        sparkTask.setConfig("{\"sql\":\"CREATE OR REPLACE TABLE iceberg.dwd.orders AS SELECT * FROM iceberg.ods.orders\"}");
        tasks.put("spark_dwd_sink", sparkTask);

        // PIPELINE edge: sync → spark
        edges.add(pipeEdge("sync_ref_ods", "spark_dwd_sink"));

        // === Step 2: validate ===
        PipelineValidationResult validation = pipelineService.validate(dagId);
        assertThat(validation.valid()).as("validate should pass").isTrue();

        // === Step 3: status DRAFT → VALIDATED ===
        Dag validated = pipelineService.updatePipelineStatus(dagId, "VALIDATED");
        assertThat(validated.getStatus()).isEqualTo("VALIDATED");
        assertThat(validated.getVersion()).isEqualTo(2);

        // === Step 4: VALIDATED → PUBLISHED (emits pipeline.published) ===
        Dag published = pipelineService.updatePipelineStatus(dagId, "PUBLISHED");
        assertThat(published.getStatus()).isEqualTo("PUBLISHED");
        assertThat(published.getVersion()).isEqualTo(3);
        assertThat(outboxTypes).contains("pipeline.published");
        Map<String, Object> publishedPayload = outboxPayloads.get(outboxTypes.indexOf("pipeline.published"));
        assertThat(publishedPayload.get("pipelineId")).isEqualTo(dagId.toString());
        assertThat(publishedPayload.get("version")).isEqualTo(3);
        assertThat(publishedPayload.get("targetFqns"))
                .isInstanceOf(java.util.List.class);
        @SuppressWarnings("unchecked")
        java.util.List<String> publishedTargets = (java.util.List<String>) publishedPayload.get("targetFqns");
        assertThat(publishedTargets)
                .containsExactlyInAnyOrder("iceberg.ods.orders", "iceberg.dwd.orders");

        // === Step 5: trigger ===
        when(dagster.launch(anyString(), anyString(), anyString(), any(), anyList()))
                .thenReturn("dagster-run-xyz");
        UUID runId = orchestrationService.triggerPipelineRun(dagId, TriggerType.MANUAL);

        JobRun run = latestRun.get();
        assertThat(run).isNotNull();
        assertThat(run.getStatus().name()).isEqualTo("RUNNING");
        assertThat(run.getDagsterRunId()).isEqualTo("dagster-run-xyz");
        // SYNC_REF is an already satisfied event dependency in run instances;
        // Spark task is the first runnable node and is sent to the execution bundle.
        assertThat(taskRuns).hasSize(2);
        assertThat(taskRuns).extracting(TaskRun::getTaskKey)
                .containsExactly("sync_ref_ods", "spark_dwd_sink");
        assertThat(taskRuns).extracting(TaskRun::getStatus)
                .containsExactly(TaskRunStatus.SUCCEEDED, TaskRunStatus.RUNNING);

        // === Step 6: refresh status to terminal (SUCCEEDED) ===
        // Simulate Dagster reporting SUCCESS
        when(dagster.getRunStatus("dagster-run-xyz"))
                .thenReturn(new DagsterClient.RunStatus("dagster-run-xyz", "SUCCESS",
                        Instant.now().minusSeconds(60), Instant.now()));
        orchestrationService.listRuns(org.springframework.data.domain.PageRequest.of(0, 10));
        // ^ listRuns internally calls refreshRunStatus → triggers terminal Outbox events
        assertThat(taskRuns).extracting(TaskRun::getStatus)
                .containsExactly(TaskRunStatus.SUCCEEDED, TaskRunStatus.SUCCEEDED);

        // === Step 7: verify Outbox events ===
        // Expect at least: pipeline.run.succeeded + 1x pipeline.task.loaded
        assertThat(outboxTypes).contains("pipeline.run.succeeded");
        long taskLoadedCount = outboxTypes.stream().filter("pipeline.task.loaded"::equals).count();
        assertThat(taskLoadedCount).as("each SUCCEEDED task_run emits pipeline.task.loaded").isEqualTo(1);

        // Verify pipeline.run.succeeded payload
        int runSucceededIdx = outboxTypes.indexOf("pipeline.run.succeeded");
        Map<String, Object> runPayload = outboxPayloads.get(runSucceededIdx);
        assertThat(runPayload.get("pipelineId")).isEqualTo(dagId.toString());
        assertThat(runPayload.get("dagsterRunId")).isEqualTo("dagster-run-xyz");
    }

    @Test
    @DisplayName("ODS→DWD template creates 3-task pipeline with proper edges")
    void odsDwdTemplateHappyPath() {
        OdsDwdTemplateResult result = pipelineService.applyOdsDwdTemplate(new OdsDwdTemplateRequest(
                "ods_dwd_test", modelId, "iceberg.ods.orders", "iceberg.dwd.orders",
                "dwd_orders", true, true));

        assertThat(result.pipelineId()).isEqualTo(dagId);
        assertThat(result.taskIds()).hasSize(4);  // SYNC_REF + Spark governance + Spark sink + QUALITY_GATE
        assertThat(edges).hasSizeGreaterThanOrEqualTo(3); // sync→governance, governance→sink, sink→gate
        assertThat(tasks.keySet())
                .contains("sync_ref_ods", "spark_field_governance", "spark_dwd_sink", "quality_gate");
    }

    @Test
    @DisplayName("Backfill dry-run doesn't write, execute creates pipeline per model")
    void backfillDryRunAndExecute() {
        // pretend there's one validated data_model in modeling
        UUID anotherModel = UUID.randomUUID();
        when(jdbc.queryForList(anyString(), eq(tenantId), eq("VALIDATED")))
                .thenReturn(List.of(Map.of(
                        "id", anotherModel,
                        "name", "dwd_orders",
                        "dbt_model_name", "dwd_orders",
                        "source_fqn", "iceberg.ods.orders",
                        "target_fqn", "iceberg.dwd.orders",
                        "status", "VALIDATED")));

        PipelineBackfillService backfillService = new PipelineBackfillService(
                dagRepo, taskRepo, edgeRepo, jdbc);

        // dry-run
        var dryRun = backfillService.backfill(true);
        assertThat(dryRun.dryRun()).isTrue();
        assertThat(dryRun.plannedItems()).hasSize(1);
        assertThat(tasks).isEmpty(); // nothing written

        // execute
        var executed = backfillService.backfill(false);
        assertThat(executed.createdPipelineIds()).isNotEmpty();
    }

    @Test
    @DisplayName("Validation fails when Spark SQL task has no sql config")
    void validationFailsForC1Violation() {
        PipelineTask t = new PipelineTask();
        t.setId(UUID.randomUUID());
        t.setTenantId(tenantId);
        t.setDagId(dagId);
        t.setTaskKey("bad_sql");
        t.setTaskType(TaskType.SPARK_SQL);
        t.setName("bad");
        t.setEngine("SPARK");
        t.setTargetFqn("iceberg.dwd.bad_sql");
        t.setConfig("{}");
        tasks.put("bad_sql", t);

        PipelineValidationResult result = pipelineService.validate(dagId);

        assertThat(result.valid()).isFalse();
        assertThat(result.taskResults().get(0).errorMessage()).contains("config.sql");
    }

    @Test
    @DisplayName("Cannot publish from DRAFT directly (must VALIDATED first)")
    void cannotSkipValidated() {
        assertThatThrownBy(() -> pipelineService.updatePipelineStatus(dagId, "PUBLISHED"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("VALIDATED");
    }

    @Test
    @DisplayName("Cannot mark as VALIDATED if validation fails")
    void cannotValidateIfCompileFails() {
        PipelineTask t = new PipelineTask();
        t.setId(UUID.randomUUID());
        t.setTenantId(tenantId);
        t.setDagId(dagId);
        t.setTaskKey("broken");
        t.setTaskType(TaskType.SPARK_SQL);
        t.setName("broken");
        t.setEngine("SPARK");
        t.setConfig("{\"sql\":\"select 1\"}");
        tasks.put("broken", t);

        assertThatThrownBy(() -> pipelineService.updatePipelineStatus(dagId, "VALIDATED"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("校验未通过");
    }

    @Test
    @DisplayName("Trigger fails when no executable tasks")
    void triggerFailsWithoutExecutableTasks() {
        // Add a SYNC_REF (not executable) — must be structurally valid for compile to pass,
        // then fail at the executableCount check.
        PipelineTask sync = new PipelineTask();
        sync.setId(UUID.randomUUID());
        sync.setTenantId(tenantId);
        sync.setDagId(dagId);
        sync.setTaskKey("sync_only");
        sync.setTaskType(TaskType.SYNC_REF);
        sync.setName("only sync");
        sync.setEngine("SPARK_SQL");
        sync.setTargetFqn("iceberg.ods.x");
        sync.setConfig("{}");
        sync.setSyncTaskId(UUID.randomUUID());  // make it valid structurally
        tasks.put("sync_only", sync);

        assertThatThrownBy(() -> orchestrationService.triggerPipelineRun(dagId, TriggerType.MANUAL))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("可执行任务");
    }

    // ---------- helpers ----------

    private PipelineTaskEdge pipeEdge(String src, String tgt) {
        PipelineTaskEdge e = new PipelineTaskEdge();
        e.setTenantId(tenantId);
        e.setDagId(dagId);
        e.setSourceKey(src);
        e.setTargetKey(tgt);
        e.setEdgeLayer(EdgeLayer.PIPELINE);
        return e;
    }

}
