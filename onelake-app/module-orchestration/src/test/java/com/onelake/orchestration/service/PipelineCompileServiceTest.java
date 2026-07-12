package com.onelake.orchestration.service;

import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.orchestration.domain.entity.Dag;
import com.onelake.orchestration.domain.entity.PipelineTask;
import com.onelake.orchestration.domain.entity.PipelineTaskEdge;
import com.onelake.orchestration.domain.enums.EdgeLayer;
import com.onelake.orchestration.domain.enums.TaskCompileStatus;
import com.onelake.orchestration.domain.enums.TaskCategory;
import com.onelake.orchestration.domain.enums.TaskType;
import com.onelake.orchestration.dto.PipelineCompileResult;
import com.onelake.orchestration.repository.DagRepository;
import com.onelake.orchestration.repository.PipelineTaskEdgeRepository;
import com.onelake.orchestration.repository.PipelineTaskRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link PipelineCompileService} 的 P1 单元测试。
 *
 * <p>覆盖 C1 单一事实来源、C2 基于 PIPELINE 边的拓扑排序、环路检测和节点级校验。
 */
@ExtendWith(MockitoExtension.class)
class PipelineCompileServiceTest {

    @Mock private DagRepository dagRepo;
    @Mock private PipelineTaskRepository taskRepo;
    @Mock private PipelineTaskEdgeRepository edgeRepo;

    private PipelineCompileService service;

    private UUID tenantId;
    private UUID dagId;

    @BeforeEach
    void setup() {
        service = new PipelineCompileService(
                dagRepo, taskRepo, edgeRepo, new ScriptSandboxPolicy(true, "*"));
        tenantId = UUID.randomUUID();
        dagId = UUID.randomUUID();
        TenantContext.setTenantId(tenantId);
    }

    @AfterEach
    void teardown() {
        TenantContext.clear();
    }

    @Test
    void compilesTwoSparkSqlTasksInTopoOrder() {
        Dag dag = newPipelineDag();
        when(dagRepo.findByIdAndTenantId(dagId, tenantId)).thenReturn(Optional.of(dag));

        PipelineTask upstream = sparkSqlTask("t_upstream", "iceberg.dwd.orders");
        PipelineTask downstream = sparkSqlTask("t_downstream", "iceberg.dwd.order_items");
        downstream.setConfig("{\"sql\":\"select ${upstream.t_upstream.rowsWritten}\"}");
        when(taskRepo.findByDagIdOrderByCreatedAtAsc(dagId))
                .thenReturn(List.of(upstream, downstream));

        PipelineTaskEdge edge = edge("t_upstream", "t_downstream", EdgeLayer.PIPELINE);
        when(edgeRepo.findByDagId(dagId)).thenReturn(List.of(edge));

        PipelineCompileResult result = service.compile(dagId);

        assertThat(result.allValidated()).isTrue();
        assertThat(result.graphErrors()).isEmpty();
        // 拓扑顺序应为上游在前、下游在后。
        assertThat(result.tasks()).extracting("taskKey")
                .containsExactly("t_upstream", "t_downstream");
        assertThat(result.pipelineTag()).startsWith("pipeline_");
    }

    @Test
    void compilesSnapshotCollectionsWithoutRepositoryReads() {
        PipelineTask task = sparkSqlTask("snapshot_task", "iceberg.dwd.snapshot");

        PipelineCompileResult result = service.compile(
                dagId, tenantId, List.of(task), List.of());

        assertThat(result.allValidated()).isTrue();
        assertThat(result.tasks()).extracting("taskKey").containsExactly("snapshot_task");
        assertThat(task.getExecutable()).isTrue();
        verifyNoInteractions(dagRepo, taskRepo, edgeRepo);
    }

    @Test
    void rejectsUpstreamOutputReferenceWithoutPipelineAncestry() {
        Dag dag = newPipelineDag();
        when(dagRepo.findByIdAndTenantId(dagId, tenantId)).thenReturn(Optional.of(dag));

        PipelineTask sibling = sparkSqlTask("sibling", "iceberg.dwd.sibling");
        PipelineTask consumer = sparkSqlTask("consumer", "iceberg.dwd.consumer");
        consumer.setConfig("{\"sql\":\"select ${upstream.sibling.rowsWritten}\"}");
        when(taskRepo.findByDagIdOrderByCreatedAtAsc(dagId))
                .thenReturn(List.of(sibling, consumer));
        when(edgeRepo.findByDagId(dagId)).thenReturn(List.of());

        PipelineCompileResult result = service.compile(dagId);

        assertThat(result.allValidated()).isFalse();
        assertThat(result.graphErrors())
                .contains("Task consumer references non-upstream task: sibling");
    }

    @Test
    void detectsCycleInPipelineLayer() {
        Dag dag = newPipelineDag();
        when(dagRepo.findByIdAndTenantId(dagId, tenantId)).thenReturn(Optional.of(dag));

        PipelineTask a = sparkSqlTask("a", "iceberg.dwd.a");
        PipelineTask b = sparkSqlTask("b", "iceberg.dwd.b");
        when(taskRepo.findByDagIdOrderByCreatedAtAsc(dagId)).thenReturn(List.of(a, b));
        when(edgeRepo.findByDagId(dagId)).thenReturn(List.of(
                edge("a", "b", EdgeLayer.PIPELINE),
                edge("b", "a", EdgeLayer.PIPELINE)));

        PipelineCompileResult result = service.compile(dagId);

        assertThat(result.allValidated()).isFalse();
        assertThat(result.graphErrors()).anyMatch(e -> e.toLowerCase().contains("cycle"));
    }

    @Test
    void rejectsSparkSqlWithoutTargetFqn() {
        Dag dag = newPipelineDag();
        when(dagRepo.findByIdAndTenantId(dagId, tenantId)).thenReturn(Optional.of(dag));

        PipelineTask t = sparkSqlTask("t_bad", null);
        when(taskRepo.findByDagIdOrderByCreatedAtAsc(dagId)).thenReturn(List.of(t));
        when(edgeRepo.findByDagId(dagId)).thenReturn(List.of());

        PipelineCompileResult result = service.compile(dagId);

        assertThat(result.allValidated()).isFalse();
        assertThat(result.tasks().get(0).valid()).isFalse();
        assertThat(result.tasks().get(0).errorMessage()).contains("targetFqn");
    }

    @Test
    void rejectsSparkSqlWithoutSqlConfig() {
        Dag dag = newPipelineDag();
        when(dagRepo.findByIdAndTenantId(dagId, tenantId)).thenReturn(Optional.of(dag));

        PipelineTask t = sparkSqlTask("t_missing_sql", "iceberg.dwd.c1");
        t.setConfig("{}");
        when(taskRepo.findByDagIdOrderByCreatedAtAsc(dagId)).thenReturn(List.of(t));
        when(edgeRepo.findByDagId(dagId)).thenReturn(List.of());

        PipelineCompileResult result = service.compile(dagId);

        assertThat(result.tasks().get(0).valid()).isFalse();
        assertThat(result.tasks().get(0).errorMessage()).contains("config.sql");
    }

    @Test
    void rejectsInvalidDynamicTimeExpressionDuringCompile() {
        Dag dag = newPipelineDag();
        when(dagRepo.findByIdAndTenantId(dagId, tenantId)).thenReturn(Optional.of(dag));

        PipelineTask t = sparkSqlTask("t_invalid_param", "iceberg.dwd.invalid_param");
        t.setConfig("{\"sql\":\"SELECT '${bizdate--1}'\"}");
        when(taskRepo.findByDagIdOrderByCreatedAtAsc(dagId)).thenReturn(List.of(t));
        when(edgeRepo.findByDagId(dagId)).thenReturn(List.of());

        PipelineCompileResult result = service.compile(dagId);

        assertThat(result.tasks().get(0).valid()).isFalse();
        assertThat(result.tasks().get(0).errorMessage())
                .contains("parameter expression invalid")
                .contains("bizdate--1");
        assertThat(t.getCompileStatus()).isEqualTo(TaskCompileStatus.FAILED);
    }

    @Test
    void pysparkRequiresScriptConfig() {
        Dag dag = newPipelineDag();
        when(dagRepo.findByIdAndTenantId(dagId, tenantId)).thenReturn(Optional.of(dag));

        PipelineTask t = sparkSqlTask("py_task", "iceberg.dwd.py");
        t.setTaskType(TaskType.PYSPARK);
        t.setEngine("PYSPARK");
        t.setConfig("{}");
        when(taskRepo.findByDagIdOrderByCreatedAtAsc(dagId)).thenReturn(List.of(t));
        when(edgeRepo.findByDagId(dagId)).thenReturn(List.of());

        PipelineCompileResult result = service.compile(dagId);

        assertThat(result.tasks().get(0).valid()).isFalse();
        assertThat(result.tasks().get(0).errorMessage()).contains("config.script");
    }

    @Test
    void sparkTaskWithTargetFqnCompilesAsExecutable() {
        Dag dag = newPipelineDag();
        when(dagRepo.findByIdAndTenantId(dagId, tenantId)).thenReturn(Optional.of(dag));

        PipelineTask t = new PipelineTask();
        t.setId(UUID.randomUUID());
        t.setTenantId(tenantId);
        t.setDagId(dagId);
        t.setTaskKey("spark_etl");
        t.setTaskType(TaskType.SPARK_SQL);
        t.setName("spark_etl");
        t.setEngine("SPARK_SQL");
        t.setTargetFqn("iceberg.dwd.spark_etl");
        t.setConfig("{\"sql\":\"SELECT 1\"}");
        when(taskRepo.findByDagIdOrderByCreatedAtAsc(dagId)).thenReturn(List.of(t));
        when(edgeRepo.findByDagId(dagId)).thenReturn(List.of());

        PipelineCompileResult result = service.compile(dagId);

        assertThat(result.tasks().get(0).valid()).isTrue();
        assertThat(result.tasks().get(0).errorMessage()).isNull();
        assertThat(t.getCompileStatus()).isEqualTo(TaskCompileStatus.VALIDATED);
        assertThat(t.getExecutable()).isTrue();
    }

    @Test
    void extensionTaskTypesPassBaselineValidationAndReceiveServerCategory() {
        List<PipelineTask> tasks = List.of(
                extensionTask("trino", TaskType.TRINO_SQL),
                extensionTask("python", TaskType.PYTHON),
                extensionTask("shell", TaskType.SHELL),
                extensionTask("branch", TaskType.BRANCH),
                extensionTask("condition", TaskType.CONDITION),
                extensionTask("sensor", TaskType.SENSOR),
                extensionTask("wait", TaskType.WAIT),
                extensionTask("sub_pipeline", TaskType.SUB_PIPELINE),
                extensionTask("notify", TaskType.NOTIFY),
                extensionTask("assertion", TaskType.ASSERTION));

        PipelineCompileResult result = service.compile(
                dagId, tenantId, tasks, List.of(edge("branch", "condition", EdgeLayer.PIPELINE)));

        assertThat(result.allValidated()).isTrue();
        assertThat(tasks).allSatisfy(task -> {
            assertThat(task.getCompileStatus()).isEqualTo(TaskCompileStatus.VALIDATED);
            assertThat(task.getCategory()).isEqualTo(task.getTaskType().category());
            assertThat(task.getExecutable()).isEqualTo(task.getCategory() == TaskCategory.EXEC);
        });
        assertThat(TaskType.BRANCH.category()).isEqualTo(TaskCategory.CONTROL);
        assertThat(TaskType.SENSOR.category()).isEqualTo(TaskCategory.OBSERVE);
        assertThat(TaskType.QUALITY_GATE.category()).isEqualTo(TaskCategory.EXEC);
    }

    @Test
    void extensionTaskRequiresNonEmptyObjectConfig() {
        PipelineTask task = extensionTask("trino", TaskType.TRINO_SQL);
        task.setConfig("{}");

        PipelineCompileResult result = service.compile(dagId, tenantId, List.of(task), List.of());

        assertThat(result.allValidated()).isFalse();
        assertThat(result.tasks().get(0).errorMessage()).contains("valid object config");
    }

    @Test
    void conditionAndBranchRequireCompleteControlConfig() {
        PipelineTask condition = extensionTask("condition", TaskType.CONDITION);
        condition.setConfig("{\"enabled\":true}");
        PipelineTask branch = extensionTask("branch", TaskType.BRANCH);
        branch.setConfig("{\"expression\":\"${env}\"}");

        PipelineCompileResult result = service.compile(
                dagId, tenantId, List.of(condition, branch), List.of());

        assertThat(result.allValidated()).isFalse();
        assertThat(result.tasks()).extracting(PipelineCompileResult.TaskCompileResult::errorMessage)
                .anySatisfy(message -> assertThat(message).contains("CONDITION").contains("expression"))
                .anySatisfy(message -> assertThat(message).contains("BRANCH").contains("branches"));
    }

    @Test
    void sensorAndWaitRequireBoundedObserveConfig() {
        PipelineTask missingTarget = extensionTask("sensor_target", TaskType.SENSOR);
        missingTarget.setConfig("{\"timeoutSeconds\":60,\"pollIntervalSeconds\":5,\"onTimeout\":\"FAILED\"}");
        PipelineTask unboundedSensor = extensionTask("sensor_timeout", TaskType.SENSOR);
        unboundedSensor.setConfig("""
                {"assetFqn":"onelake.ods.orders","timeoutSeconds":86401,
                 "pollIntervalSeconds":5,"onTimeout":"FAILED"}
                """);
        PipelineTask ambiguousWait = extensionTask("wait_ambiguous", TaskType.WAIT);
        ambiguousWait.setConfig("{\"offsetSeconds\":60,\"durationSeconds\":5}");

        PipelineCompileResult result = service.compile(
                dagId, tenantId, List.of(missingTarget, unboundedSensor, ambiguousWait), List.of());

        assertThat(result.allValidated()).isFalse();
        assertThat(result.tasks()).extracting(PipelineCompileResult.TaskCompileResult::errorMessage)
                .anySatisfy(message -> assertThat(message).contains("SENSOR").contains("assetFqn"))
                .anySatisfy(message -> assertThat(message).contains("timeoutSeconds").contains("86400"))
                .anySatisfy(message -> assertThat(message).contains("exactly one").contains("durationSeconds"));
    }

    @Test
    void observeNodesCompileWithoutDeclaringAssetOutput() {
        PipelineTask sensor = extensionTask("sensor", TaskType.SENSOR);
        sensor.setTargetFqn(null);
        PipelineTask wait = extensionTask("wait", TaskType.WAIT);
        wait.setTargetFqn(null);
        PipelineTask downstream = sparkSqlTask("downstream", "onelake.dwd.downstream");

        PipelineCompileResult result = service.compile(
                dagId,
                tenantId,
                List.of(sensor, wait, downstream),
                List.of(
                        edge("sensor", "wait", EdgeLayer.PIPELINE),
                        edge("wait", "downstream", EdgeLayer.PIPELINE)));

        assertThat(result.allValidated()).isTrue();
        assertThat(sensor.getCategory()).isEqualTo(TaskCategory.OBSERVE);
        assertThat(wait.getCategory()).isEqualTo(TaskCategory.OBSERVE);
        assertThat(sensor.getExecutable()).isFalse();
        assertThat(wait.getExecutable()).isFalse();
        assertThat(result.graphErrors()).noneSatisfy(
                message -> assertThat(message).contains("cannot resolve assetFqn"));
    }

    @Test
    void branchMappingMustCoverDirectPipelineDownstreamTasks() {
        PipelineTask branch = extensionTask("branch", TaskType.BRANCH);
        branch.setConfig("""
                {"expression":"${env}","branches":{"prod":"prod_task"}}
                """);
        PipelineTask prod = sparkSqlTask("prod_task", "onelake.dwd.prod");
        PipelineTask dev = sparkSqlTask("dev_task", "onelake.dwd.dev");

        PipelineCompileResult result = service.compile(
                dagId,
                tenantId,
                List.of(branch, prod, dev),
                List.of(
                        edge("branch", "prod_task", EdgeLayer.PIPELINE),
                        edge("branch", "dev_task", EdgeLayer.PIPELINE)));

        assertThat(result.allValidated()).isFalse();
        assertThat(result.graphErrors()).anySatisfy(
                message -> assertThat(message).contains("unmapped direct downstream").contains("dev_task"));
    }

    @Test
    void scriptTaskCompilesWithConservativeSandboxLimits() {
        PipelineTask task = extensionTask("python_safe", TaskType.PYTHON);
        task.setConfig("""
            {
              "script":"print('hello')",
              "timeout_seconds":20,
              "cpu_seconds":10,
              "cpu_cores":1,
              "memory_mb":128,
              "max_processes":4,
              "stdout_max_bytes":8192,
              "stderr_max_bytes":8192,
              "env":{"BIZ_LABEL":"daily"},
              "network_allowlist":[]
            }
            """);

        PipelineCompileResult result = service.compile(dagId, tenantId, List.of(task), List.of());

        assertThat(result.allValidated()).isTrue();
        assertThat(task.getExecutable()).isTrue();
    }

    @Test
    void scriptTaskRejectsTenantWithoutSandboxCapability() {
        PipelineCompileService denied = new PipelineCompileService(
                dagRepo, taskRepo, edgeRepo,
                new ScriptSandboxPolicy(true, UUID.randomUUID().toString()));
        PipelineTask task = extensionTask("python_denied", TaskType.PYTHON);

        PipelineCompileResult result = denied.compile(
                dagId, tenantId, List.of(task), List.of());

        assertThat(result.allValidated()).isFalse();
        assertThat(result.tasks().get(0).errorMessage())
                .contains("当前租户未获 PYTHON/SHELL 沙箱能力授权");
    }

    @Test
    void scriptTaskRejectsOversizedSourceAndTimeout() {
        PipelineTask oversized = extensionTask("python_large", TaskType.PYTHON);
        oversized.setConfig("{\"script\":\"" + "x".repeat(65_537) + "\"}");
        PipelineTask timeout = extensionTask("shell_timeout", TaskType.SHELL);
        timeout.setConfig("{\"script\":\"echo ok\",\"timeout_seconds\":901}");

        PipelineCompileResult result = service.compile(
                dagId, tenantId, List.of(oversized, timeout), List.of());

        assertThat(result.allValidated()).isFalse();
        assertThat(result.tasks()).extracting(PipelineCompileResult.TaskCompileResult::errorMessage)
                .anySatisfy(error -> assertThat(error).contains("exceeds 65536 UTF-8 bytes"))
                .anySatisfy(error -> assertThat(error).contains("timeout_seconds must be between 1 and 900"));
    }

    @Test
    void scriptTaskRejectsControlPlaneCredentialAndEscapePatterns() {
        PipelineTask credential = extensionTask("python_secret", TaskType.PYTHON);
        credential.setConfig("""
            {"script":"print('safe')","env":{"ONELAKE_INTERNAL_TOKEN":"forbidden"}}
            """);
        PipelineTask escape = extensionTask("shell_escape", TaskType.SHELL);
        escape.setConfig("""
            {"script":"cat /var/run/docker.sock","network_allowlist":[]}
            """);
        PipelineTask network = extensionTask("python_network", TaskType.PYTHON);
        network.setConfig("""
            {"script":"print('network')","network_allowlist":["example.com:443"]}
            """);

        PipelineCompileResult result = service.compile(
                dagId, tenantId, List.of(credential, escape, network), List.of());

        assertThat(result.allValidated()).isFalse();
        assertThat(result.tasks()).extracting(PipelineCompileResult.TaskCompileResult::errorMessage)
                .anySatisfy(error -> assertThat(error).contains("env key is reserved"))
                .anySatisfy(error -> assertThat(error).contains("sandbox-escape pattern"))
                .anySatisfy(error -> assertThat(error).contains("networking is default-deny"));
    }

    @Test
    void trinoReadQueryCompilesWithoutMaterializationTarget() {
        PipelineTask task = extensionTask("trino_validate", TaskType.TRINO_SQL);

        PipelineCompileResult result = service.compile(dagId, tenantId, List.of(task), List.of());

        assertThat(result.allValidated()).isTrue();
        assertThat(task.getExecutable()).isTrue();
        assertThat(task.getTargetFqn()).isNull();
    }

    @Test
    void trinoCtasRequiresMatchingIcebergTarget() {
        PipelineTask task = extensionTask("trino_ctas", TaskType.TRINO_SQL);
        task.setTargetFqn("iceberg.dwd.daily_orders");
        task.setConfig("""
            {
              "sql":"CREATE TABLE dwd.daily_orders AS SELECT 1 AS order_id",
              "catalog":"iceberg",
              "schema":"dwd"
            }
            """);

        PipelineCompileResult result = service.compile(dagId, tenantId, List.of(task), List.of());

        assertThat(result.allValidated()).isTrue();
    }

    @Test
    void trinoRejectsUnapprovedWritesAndCatalogs() {
        PipelineTask write = extensionTask("trino_insert", TaskType.TRINO_SQL);
        write.setConfig("""
            {"sql":"INSERT INTO dwd.orders SELECT 1","catalog":"iceberg","schema":"dwd"}
            """);
        PipelineTask catalog = extensionTask("trino_hive", TaskType.TRINO_SQL);
        catalog.setConfig("""
            {"sql":"SELECT 1","catalog":"hive","schema":"dwd"}
            """);

        PipelineCompileResult result = service.compile(
                dagId, tenantId, List.of(write, catalog), List.of());

        assertThat(result.allValidated()).isFalse();
        assertThat(result.tasks()).extracting(PipelineCompileResult.TaskCompileResult::errorMessage)
                .anySatisfy(error -> assertThat(error).contains("read-only queries or CREATE TABLE AS SELECT"))
                .anySatisfy(error -> assertThat(error).contains("config.catalog"));
    }

    @Test
    void trinoExplainAnalyzeCannotWrapWriteStatement() {
        PipelineTask task = extensionTask("trino_explain_insert", TaskType.TRINO_SQL);
        task.setConfig("""
            {
              "sql":"EXPLAIN ANALYZE INSERT INTO dwd.orders SELECT 1",
              "catalog":"iceberg",
              "schema":"dwd"
            }
            """);

        PipelineCompileResult result = service.compile(dagId, tenantId, List.of(task), List.of());

        assertThat(result.allValidated()).isFalse();
        assertThat(result.tasks().get(0).errorMessage())
                .contains("read-only queries or CREATE TABLE AS SELECT");
    }

    @Test
    void trinoExplainAnalyzeCommentsCannotBypassWriteCheck() {
        PipelineTask task = extensionTask("trino_explain_comment_insert", TaskType.TRINO_SQL);
        task.setConfig("""
            {
              "sql":"EXPLAIN /* inspect */ ANALYZE -- execute below\\n INSERT INTO dwd.orders SELECT 1",
              "catalog":"iceberg",
              "schema":"dwd"
            }
            """);

        PipelineCompileResult result = service.compile(dagId, tenantId, List.of(task), List.of());

        assertThat(result.allValidated()).isFalse();
        assertThat(result.tasks().get(0).errorMessage())
                .contains("read-only queries or CREATE TABLE AS SELECT");
    }

    @Test
    void trinoExplainAnalyzeReadQueryRemainsAllowed() {
        PipelineTask task = extensionTask("trino_explain_select", TaskType.TRINO_SQL);
        task.setConfig("""
            {
              "sql":"EXPLAIN ANALYZE SELECT count(*) FROM dwd.orders",
              "catalog":"iceberg",
              "schema":"dwd"
            }
            """);

        PipelineCompileResult result = service.compile(dagId, tenantId, List.of(task), List.of());

        assertThat(result.allValidated()).isTrue();
    }

    @Test
    void trinoRejectsSchemaOutsideServerAllowlist() {
        PipelineTask task = extensionTask("trino_private", TaskType.TRINO_SQL);
        task.setConfig("""
            {"sql":"SELECT 1","catalog":"iceberg","schema":"private_tenant"}
            """);

        PipelineCompileResult result = service.compile(dagId, tenantId, List.of(task), List.of());

        assertThat(result.allValidated()).isFalse();
        assertThat(result.tasks().get(0).errorMessage()).contains("config.schema must be one of");
    }

    @Test
    void rejectsWhenPipelineNotFound() {
        when(dagRepo.findByIdAndTenantId(any(), any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.compile(dagId))
                .isInstanceOf(BizException.class);
    }

    @Test
    void rejectsWhenTenantContextMissing() {
        TenantContext.clear();
        assertThatThrownBy(() -> service.compile(dagId))
                .isInstanceOf(BizException.class);
        TenantContext.setTenantId(tenantId); // 为 @AfterEach 恢复租户上下文。
    }

    @Test
    void flagsDanglingEdgesAsGraphErrors() {
        Dag dag = newPipelineDag();
        when(dagRepo.findByIdAndTenantId(dagId, tenantId)).thenReturn(Optional.of(dag));
        PipelineTask t = sparkSqlTask("t_solo", "iceberg.dwd.solo");
        when(taskRepo.findByDagIdOrderByCreatedAtAsc(dagId)).thenReturn(List.of(t));
        when(edgeRepo.findByDagId(dagId)).thenReturn(List.of(
                edge("t_solo", "t_missing", EdgeLayer.PIPELINE)));

        PipelineCompileResult result = service.compile(dagId);

        assertThat(result.graphErrors()).anyMatch(e -> e.contains("t_missing"));
    }

    @Test
    void syncRefTaskRequiresTargetFqnOnly() {
        Dag dag = newPipelineDag();
        when(dagRepo.findByIdAndTenantId(dagId, tenantId)).thenReturn(Optional.of(dag));

        PipelineTask t = new PipelineTask();
        t.setId(UUID.randomUUID());
        t.setTenantId(tenantId);
        t.setDagId(dagId);
        t.setTaskKey("sync_ref");
        t.setTaskType(TaskType.SYNC_REF);
        t.setName("sync_ref");
        t.setEngine("SPARK");
        t.setConfig("{}");
        // 缺少 targetFqn。
        when(taskRepo.findByDagIdOrderByCreatedAtAsc(dagId)).thenReturn(List.of(t));
        when(edgeRepo.findByDagId(dagId)).thenReturn(List.of());

        PipelineCompileResult result = service.compile(dagId);

        assertThat(result.tasks().get(0).valid()).isFalse();
        assertThat(result.tasks().get(0).errorMessage()).contains("targetFqn");
    }

    @Test
    void syncRefTaskAllowsMissingSyncTaskIdWhenTargetFqnIsPresent() {
        Dag dag = newPipelineDag();
        when(dagRepo.findByIdAndTenantId(dagId, tenantId)).thenReturn(Optional.of(dag));

        PipelineTask t = new PipelineTask();
        t.setId(UUID.randomUUID());
        t.setTenantId(tenantId);
        t.setDagId(dagId);
        t.setTaskKey("sync_ref");
        t.setTaskType(TaskType.SYNC_REF);
        t.setName("sync_ref");
        t.setEngine("SPARK");
        t.setTargetFqn("iceberg.ods.orders");
        t.setConfig("{}");
        when(taskRepo.findByDagIdOrderByCreatedAtAsc(dagId)).thenReturn(List.of(t));
        when(edgeRepo.findByDagId(dagId)).thenReturn(List.of());

        PipelineCompileResult result = service.compile(dagId);

        assertThat(result.tasks().get(0).valid()).isTrue();
        assertThat(t.getCompileStatus()).isEqualTo(TaskCompileStatus.VALIDATED);
        assertThat(t.getExecutable()).isFalse();
    }

    // ---------- 辅助方法 ----------

    private Dag newPipelineDag() {
        Dag d = new Dag();
        d.setId(dagId);
        d.setTenantId(tenantId);
        d.setName("test_pipeline");
        d.setDagsterJob("onelake_pipeline_run");
        d.setDefinition("{}");
        d.setPipelineKind("BLANK");
        d.setStatus("DRAFT");
        d.setEngine("SPARK");
        return d;
    }

    private PipelineTask sparkSqlTask(String key, String targetFqn) {
        PipelineTask t = new PipelineTask();
        t.setId(UUID.randomUUID());
        t.setTenantId(tenantId);
        t.setDagId(dagId);
        t.setTaskKey(key);
        t.setTaskType(TaskType.SPARK_SQL);
        t.setName(key);
        t.setEngine("SPARK");
        t.setConfig("{\"sql\":\"select * from source\"}");
        t.setTargetFqn(targetFqn);
        return t;
    }

    private PipelineTask extensionTask(String key, TaskType type) {
        PipelineTask task = new PipelineTask();
        task.setId(UUID.randomUUID());
        task.setTenantId(tenantId);
        task.setDagId(dagId);
        task.setTaskKey(key);
        task.setTaskType(type);
        task.setName(key);
        task.setEngine(type.name());
        task.setConfig(switch (type) {
            case TRINO_SQL -> "{\"sql\":\"SELECT 1\",\"catalog\":\"iceberg\",\"schema\":\"dwd\"}";
            case PYTHON -> "{\"script\":\"print('ok')\"}";
            case SHELL -> "{\"script\":\"echo ok\"}";
            case CONDITION -> "{\"expression\":\"true\"}";
            case BRANCH -> "{\"expression\":\"condition\",\"branches\":{\"condition\":\"condition\"}}";
            case SENSOR -> """
                    {"assetFqn":"onelake.ods.orders","partition":"2026-07-12",
                     "timeoutSeconds":60,"pollIntervalSeconds":5,"onTimeout":"FAILED"}
                    """;
            case WAIT -> "{\"durationSeconds\":1}";
            default -> "{\"enabled\":true}";
        });
        return task;
    }

    private PipelineTaskEdge edge(String src, String tgt, EdgeLayer layer) {
        PipelineTaskEdge e = new PipelineTaskEdge();
        e.setTenantId(tenantId);
        e.setDagId(dagId);
        e.setSourceKey(src);
        e.setTargetKey(tgt);
        e.setEdgeLayer(layer);
        e.setSourcePort("out");
        e.setTargetPort("in");
        return e;
    }
}
