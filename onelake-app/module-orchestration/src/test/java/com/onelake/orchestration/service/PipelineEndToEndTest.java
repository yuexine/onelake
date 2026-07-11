package com.onelake.orchestration.service;

import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.outbox.OutboxPublisher;
import com.onelake.common.system.entity.TenantEntity;
import com.onelake.common.system.repository.TenantRepository;
import com.onelake.orchestration.client.DagsterClient;
import com.onelake.orchestration.domain.entity.Dag;
import com.onelake.orchestration.domain.entity.JobRun;
import com.onelake.orchestration.domain.entity.PipelineTask;
import com.onelake.orchestration.domain.entity.PipelineTaskEdge;
import com.onelake.orchestration.domain.entity.PipelineVersion;
import com.onelake.orchestration.domain.entity.TaskRun;
import com.onelake.orchestration.domain.enums.EdgeLayer;
import com.onelake.orchestration.domain.enums.RunEnvironment;
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
import com.onelake.orchestration.repository.PipelineParamRepository;
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
 * 编排端到端测试：串起流水线 V2 的完整生命周期。
 *
 * <p>该测试跨越 {@link PipelineService}、{@link PipelineCompileService}、
 * {@link OrchestrationService} 和 {@link OutboxPublisher}，在不依赖真实数据库的情况下
 * 验证服务间契约与事件流。
 *
 * <p>覆盖生产主路径：
 * <ol>
 *   <li>创建空白流水线。</li>
 *   <li>添加 SYNC_REF + SPARK_SQL 节点。</li>
 *   <li>校验 Spark 可执行基线。</li>
 *   <li>状态从 DRAFT 流转到 VALIDATED。</li>
 *   <li>状态从 VALIDATED 流转到 PUBLISHED，并发布 pipeline.published。</li>
 *   <li>触发运行，为每个节点创建 TaskRun 并启动 Dagster。</li>
 *   <li>刷新终态，发布 pipeline.run.succeeded 与 pipeline.task.loaded。</li>
 * </ol>
 *
 * <p>这是覆盖跨服务装配问题的集成锚点，补足单服务单元测试可能遗漏的链路问题。
 */
@ExtendWith(MockitoExtension.class)
class PipelineEndToEndTest {

    @Mock private DagRepository dagRepo;
    @Mock private PipelineTaskRepository taskRepo;
    @Mock private PipelineTaskEdgeRepository edgeRepo;
    @Mock private PipelineParamRepository paramRepo;
    @Mock private TaskRunRepository taskRunRepo;
    @Mock private JobRunRepository runRepo;
    @Mock private DagsterClient dagster;
    @Mock private JdbcTemplate jdbc;
    @Mock private ObjectProvider<OutboxPublisher> outboxProvider;
    @Mock private OutboxPublisher outboxPublisher;
    @Mock private RuntimeContractService runtimeContractService;
    @Mock private PipelineLogStorage pipelineLogStorage;
    @Mock private ParamResolver paramResolver;
    @Mock private PipelineSnapshotService snapshotService;
    @Mock private TenantRepository tenantRepo;

    private PipelineService pipelineService;
    private PipelineCompileService compileService;
    private OrchestrationService orchestrationService;

    // 每个测试用例独立维护的内存流水线状态。
    private UUID tenantId;
    private UUID dagId;
    private UUID modelId;
    private Dag dag;
    private final Map<String, PipelineTask> tasks = new LinkedHashMap<>();
    private final List<PipelineTaskEdge> edges = new java.util.ArrayList<>();
    private final AtomicReference<PipelineVersion> publishedVersion = new AtomicReference<>();
    private final AtomicReference<JobRun> latestRun = new AtomicReference<>();
    private final List<TaskRun> taskRuns = new java.util.ArrayList<>();
    private final List<Map<String, Object>> outboxPayloads = new java.util.ArrayList<>();
    private final List<String> outboxTypes = new java.util.ArrayList<>();

    @BeforeEach
    void setup() {
        compileService = new PipelineCompileService(dagRepo, taskRepo, edgeRepo);
        pipelineService = new PipelineService(dagRepo, taskRepo, edgeRepo, paramRepo, taskRunRepo,
                runRepo, compileService, snapshotService, outboxProvider, tenantRepo);
        orchestrationService = new OrchestrationService(dagRepo, runRepo, dagster, jdbc,
                runtimeContractService, compileService, snapshotService, taskRepo, edgeRepo, taskRunRepo,
                new SparkRunConfigBuilder(paramResolver), outboxProvider, pipelineLogStorage, new DataIntervalCalculator());

        tenantId = UUID.randomUUID();
        dagId = UUID.randomUUID();
        modelId = UUID.randomUUID();
        TenantContext.setTenantId(tenantId);
        TenantContext.setUserId(UUID.randomUUID());
        lenient().when(tenantRepo.findByIdForUpdate(tenantId))
                .thenReturn(Optional.of(new TenantEntity()));

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
        lenient().when(snapshotService.publishSnapshot(eq(dagId))).thenAnswer(inv -> {
            PipelineVersion version = new PipelineVersion();
            version.setId(UUID.randomUUID());
            version.setDagId(dagId);
            version.setTenantId(tenantId);
            dag.setPublishedVersionId(version.getId());
            dag.setHasUnpublishedChanges(false);
            publishedVersion.set(version);
            return version;
        });
        lenient().when(snapshotService.loadExecutionSnapshot(any(), eq(dagId))).thenAnswer(inv ->
                new PipelineSnapshotService.ExecutionSnapshot(
                        publishedVersion.get(),
                        dag,
                        new java.util.ArrayList<>(tasks.values()),
                        new java.util.ArrayList<>(edges),
                        List.of()));
        lenient().when(runtimeContractService.launchBlockedReason(anyString(), any()))
                .thenReturn(Optional.empty());

        // 将 Repository mock 接到内存状态上，模拟最小持久化行为。
        lenient().when(dagRepo.findByIdAndTenantId(eq(dagId), eq(tenantId))).thenReturn(Optional.of(dag));
        lenient().when(dagRepo.findByIdForUpdate(eq(dagId))).thenReturn(Optional.of(dag));
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

        // 捕获 Outbox 发布内容，供后续断言。
        lenient().when(outboxProvider.getIfAvailable()).thenReturn(outboxPublisher);
        lenient().doAnswer(inv -> {
            outboxTypes.add(inv.getArgument(0));
            outboxPayloads.add(inv.getArgument(2));
            return null;
        }).when(outboxPublisher).publish(anyString(), anyString(), any());

        // 运行契约：V2 主路径在测试中保持可触发。
        lenient().when(runtimeContractService.triggerBlockedReason(anyString(), any())).thenReturn(Optional.empty());
    }

    @AfterEach
    void teardown() {
        tasks.clear();
        edges.clear();
        taskRuns.clear();
        outboxPayloads.clear();
        outboxTypes.clear();
        publishedVersion.set(null);
        TenantContext.clear();
    }

    @Test
    @DisplayName("完整生命周期：创建 → 添加 Spark 节点 → 校验 → 发布 → 触发 → 终态 → Outbox")
    void fullLifecycleHappyPath() {
        // === 步骤 1：通过服务创建节点，模拟 API 调用 ===
        PipelineTask syncTask = new PipelineTask();
        syncTask.setId(UUID.randomUUID());
        syncTask.setTenantId(tenantId);
        syncTask.setDagId(dagId);
        syncTask.setTaskKey("sync_ref_ods");
        syncTask.setTaskType(TaskType.SYNC_REF);
        syncTask.setName("ODS source");
        syncTask.setEngine("SPARK_SQL");
        syncTask.setTargetFqn("iceberg.ods.orders");
        syncTask.setSyncTaskId(UUID.randomUUID());  // 结构上有效。
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

        // PIPELINE 边：sync → spark。
        edges.add(pipeEdge("sync_ref_ods", "spark_dwd_sink"));

        // === 步骤 2：校验 ===
        PipelineValidationResult validation = pipelineService.validate(dagId);
        assertThat(validation.valid()).as("validate should pass").isTrue();

        // === 步骤 3：状态 DRAFT → VALIDATED ===
        Dag validated = pipelineService.updatePipelineStatus(dagId, "VALIDATED");
        assertThat(validated.getStatus()).isEqualTo("VALIDATED");
        assertThat(validated.getVersion()).isEqualTo(2);

        // === 步骤 4：状态 VALIDATED → PUBLISHED，并发布 pipeline.published ===
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

        // === 步骤 5：触发运行 ===
        when(dagster.launch(anyString(), anyString(), anyString(), any(), anyList()))
                .thenReturn("dagster-run-xyz");
        UUID runId = orchestrationService.triggerPipelineRun(dagId, TriggerType.MANUAL);

        JobRun run = latestRun.get();
        assertThat(run).isNotNull();
        assertThat(run.getStatus().name()).isEqualTo("RUNNING");
        assertThat(run.getDagsterRunId()).isEqualTo("dagster-run-xyz");
        // SYNC_REF 在运行实例中表示已满足的事件依赖；
        // Spark 节点是第一个可运行节点，会被发送到执行包。
        assertThat(taskRuns).hasSize(2);
        assertThat(taskRuns).extracting(TaskRun::getTaskKey)
                .containsExactly("sync_ref_ods", "spark_dwd_sink");
        assertThat(taskRuns).extracting(TaskRun::getStatus)
                .containsExactly(TaskRunStatus.SUCCEEDED, TaskRunStatus.RUNNING);

        // === 步骤 6：刷新到终态 SUCCEEDED ===
        // 模拟 Dagster 返回 SUCCESS。
        when(dagster.getRunStatus("dagster-run-xyz"))
                .thenReturn(new DagsterClient.RunStatus("dagster-run-xyz", "SUCCESS",
                        Instant.now().minusSeconds(60), Instant.now()));
        orchestrationService.listRuns(org.springframework.data.domain.PageRequest.of(0, 10));
        // listRuns 内部会调用 refreshRunStatus，并触发终态 Outbox 事件。
        assertThat(taskRuns).extracting(TaskRun::getStatus)
                .containsExactly(TaskRunStatus.SUCCEEDED, TaskRunStatus.SUCCEEDED);

        // === 步骤 7：校验 Outbox 事件 ===
        // 至少应包含 pipeline.run.succeeded 和一条 pipeline.task.loaded。
        assertThat(outboxTypes).contains("pipeline.run.succeeded");
        long taskLoadedCount = outboxTypes.stream().filter("pipeline.task.loaded"::equals).count();
        assertThat(taskLoadedCount).as("each SUCCEEDED task_run emits pipeline.task.loaded").isEqualTo(1);

        // 校验 pipeline.run.succeeded 载荷。
        int runSucceededIdx = outboxTypes.indexOf("pipeline.run.succeeded");
        Map<String, Object> runPayload = outboxPayloads.get(runSucceededIdx);
        assertThat(runPayload.get("pipelineId")).isEqualTo(dagId.toString());
        assertThat(runPayload.get("dagsterRunId")).isEqualTo("dagster-run-xyz");
    }

    @Test
    @DisplayName("ODS→DWD 模板会创建标准节点和边")
    void odsDwdTemplateHappyPath() {
        OdsDwdTemplateResult result = pipelineService.applyOdsDwdTemplate(new OdsDwdTemplateRequest(
                "ods_dwd_test", modelId, "iceberg.ods.orders", "iceberg.dwd.orders",
                "dwd_orders", true, true));

        assertThat(result.pipelineId()).isEqualTo(dagId);
        assertThat(result.taskIds()).hasSize(4);  // SYNC_REF + Spark 治理 + Spark 落表 + QUALITY_GATE。
        assertThat(edges).hasSizeGreaterThanOrEqualTo(3); // sync→governance、governance→sink、sink→gate。
        assertThat(tasks.keySet())
                .contains("sync_ref_ods", "spark_field_governance", "spark_dwd_sink", "quality_gate");
    }

    @Test
    @DisplayName("模型迁移干跑不写入，执行时为每个模型创建流水线")
    void modelMigrationDryRunAndExecute() {
        // 模拟 modeling 库中存在一条已校验的历史模型。
        UUID anotherModel = UUID.randomUUID();
        when(jdbc.queryForList(anyString(), eq(tenantId), eq("VALIDATED")))
                .thenReturn(List.of(Map.of(
                        "id", anotherModel,
                        "name", "dwd_orders",
                        "dbt_model_name", "dwd_orders",
                        "source_fqn", "iceberg.ods.orders",
                        "target_fqn", "iceberg.dwd.orders",
                        "status", "VALIDATED")));

        ModelMigrationService modelMigrationService = new ModelMigrationService(
                dagRepo, taskRepo, edgeRepo, jdbc);

        // 干跑只返回迁移计划，不写入任何流水线节点。
        var dryRun = modelMigrationService.migrate(true);
        assertThat(dryRun.dryRun()).isTrue();
        assertThat(dryRun.plannedItems()).hasSize(1);
        assertThat(tasks).isEmpty(); // 未产生写入。

        // 真实执行迁移后会创建对应流水线。
        var executed = modelMigrationService.migrate(false);
        assertThat(executed.createdPipelineIds()).isNotEmpty();
    }

    @Test
    @DisplayName("Spark SQL 节点缺少 sql 配置时校验失败")
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
    @DisplayName("不能从 DRAFT 直接发布，必须先进入 VALIDATED")
    void cannotSkipValidated() {
        assertThatThrownBy(() -> pipelineService.updatePipelineStatus(dagId, "PUBLISHED"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("VALIDATED");
    }

    @Test
    @DisplayName("校验失败时不能标记为 VALIDATED")
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
    @DisplayName("没有可执行节点时触发失败")
    void triggerFailsWithoutExecutableTasks() {
        // 添加结构有效但不可执行的 SYNC_REF，让编译先通过，再在可执行节点数量检查处失败。
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
        sync.setSyncTaskId(UUID.randomUUID());  // 让结构校验通过。
        tasks.put("sync_only", sync);

        assertThatThrownBy(() -> orchestrationService.triggerPipelineRun(
                dagId, TriggerType.MANUAL, RunEnvironment.DEV))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("可执行任务");
    }

    // ---------- 辅助方法 ----------

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
