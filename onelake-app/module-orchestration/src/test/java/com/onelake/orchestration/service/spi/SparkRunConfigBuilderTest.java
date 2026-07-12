package com.onelake.orchestration.service.spi;

import com.onelake.orchestration.domain.entity.PipelineTaskEdge;
import com.onelake.orchestration.domain.enums.EdgeLayer;
import com.onelake.orchestration.domain.entity.PipelineTask;
import com.onelake.orchestration.domain.enums.TaskType;
import com.onelake.orchestration.domain.enums.TriggerType;
import com.onelake.orchestration.dto.PipelineCompileResult;
import com.onelake.orchestration.service.ParamResolver;
import com.onelake.orchestration.service.RunContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SparkRunConfigBuilderTest {

    private final ParamResolver paramResolver = mock(ParamResolver.class);
    private final SparkRunConfigBuilder builder = new SparkRunConfigBuilder(paramResolver);

    @Test
    void graphJobNameIncludesImmutableVersionWhenPresent() {
        UUID pipelineId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID versionId = UUID.fromString("00000000-0000-0000-0000-000000000099");

        assertThat(SparkRunConfigBuilder.graphJobName(pipelineId, versionId))
                .isEqualTo("onelake_pipeline_graph_00000000000000000000000000000001"
                        + "_v_00000000000000000000000000000099");
    }

    @BeforeEach
    void setup() {
        reset(paramResolver);
        when(paramResolver.resolveForTasks(
                any(), any(), anyCollection(), nullable(RunContext.class), anyMap()))
                .thenReturn(Map.of());
    }

    @Test
    @SuppressWarnings("unchecked")
    void qualityGateIsRenderedAsExecutableSparkTask() {
        UUID pipelineId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        PipelineTask gate = new PipelineTask();
        gate.setId(UUID.randomUUID());
        gate.setTenantId(tenantId);
        gate.setDagId(pipelineId);
        gate.setTaskKey("quality_gate");
        gate.setTaskType(TaskType.QUALITY_GATE);
        gate.setName("DWD 质量门禁");
        gate.setEngine("SPARK_SQL");
        gate.setTargetFqn("onelake.dwd.user");
        gate.setExecutable(true);
        gate.setConfig("""
            {
              "targetModelFqn": "onelake.dwd.user",
              "gates": [
                {"id":"primary","kind":"PRIMARY","enabled":true,"columns":["user_id"],"actionOnViolation":"FAIL"}
              ]
            }
            """);
        PipelineCompileResult compile = new PipelineCompileResult(
                pipelineId,
                "pipeline_" + pipelineId,
                tenantId,
                List.of(new PipelineCompileResult.TaskCompileResult(
                        gate.getId(), gate.getTaskKey(), TaskType.QUALITY_GATE.name(),
                        true, gate.getTargetFqn(), null)),
                true,
                List.of());
        TaskBundleContext context = new TaskBundleContext(
                pipelineId, tenantId, runId, compile,
                "pipeline_" + pipelineId, "spark-default", "spark-small");

        DagsterRunConfig config = builder.build(
                context,
                List.of(gate),
                "http://localhost:8080");

        Map<String, Object> ops = (Map<String, Object>) config.opConfig().get("ops");
        Map<String, Object> op = (Map<String, Object>) ops.get("run_spark_task_op");
        Map<String, Object> opConfig = (Map<String, Object>) op.get("config");
        List<Map<String, Object>> tasks = (List<Map<String, Object>>) opConfig.get("tasks");

        assertThat(opConfig).containsEntry("callback_base_url", "http://localhost:8080");
        assertThat(opConfig).doesNotContainKey("callback_internal_token");
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0)).containsEntry("task_key", "quality_gate");
        assertThat(tasks.get(0)).containsEntry("task_type", "QUALITY_GATE");
        assertThat((List<String>) tasks.get(0).get("from_tables")).containsExactly("onelake.dwd.user");
        assertThat(tasks.get(0).get("sql_or_script").toString())
                .contains("QUALITY_TABLE")
                .contains("onelake.dwd.user_quality_check")
                .contains("Quality gate failed");
    }

    @Test
    @SuppressWarnings("unchecked")
    void graphRunConfigIncludesNodesEdgesAndRetries() {
        UUID pipelineId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        PipelineTask sync = task(pipelineId, tenantId, "sync_user", TaskType.SYNC_REF, false, "{}");
        sync.setTargetFqn("onelake.ods.user");
        PipelineTask spark = task(pipelineId, tenantId, "spark_dwd", TaskType.SPARK_SQL, true, """
            {
              "sql": "select * from onelake.ods.user",
              "from_tables": ["onelake.ods.user"],
              "max_retries": 2
            }
            """);
        spark.setTargetFqn("onelake.dwd.user");
        PipelineCompileResult compile = new PipelineCompileResult(
                pipelineId,
                "pipeline_" + pipelineId,
                tenantId,
                List.of(
                        new PipelineCompileResult.TaskCompileResult(
                                sync.getId(), sync.getTaskKey(), sync.getTaskType().name(),
                                true, sync.getTargetFqn(), null),
                        new PipelineCompileResult.TaskCompileResult(
                                spark.getId(), spark.getTaskKey(), spark.getTaskType().name(),
                                true, spark.getTargetFqn(), null)),
                true,
                List.of());
        TaskBundleContext context = new TaskBundleContext(
                pipelineId, tenantId, runId, compile,
                "pipeline_" + pipelineId, "spark-default", "spark-small");
        PipelineTaskEdge pipelineEdge = edge("sync_user", "spark_dwd", EdgeLayer.PIPELINE);
        PipelineTaskEdge crossEngineEdge = edge("sync_user", "ignored", EdgeLayer.CROSS_ENGINE);

        DagsterRunConfig config = builder.buildGraphRunConfig(
                context,
                List.of(sync, spark),
                List.of(pipelineEdge, crossEngineEdge),
                "http://localhost:8080",
                6);

        assertThat(config.jobName()).isEqualTo(SparkRunConfigBuilder.graphJobName(pipelineId));
        Map<String, Object> ops = (Map<String, Object>) config.opConfig().get("ops");
        assertThat(ops).containsOnlyKeys("sync_user", "spark_dwd");
        Map<String, Object> sparkOp = (Map<String, Object>) ops.get("spark_dwd");
        Map<String, Object> sparkNode = (Map<String, Object>) sparkOp.get("config");
        assertThat(sparkNode).containsEntry("task_type", "SPARK_SQL");
        assertThat(sparkNode).containsEntry("base_attempt", 1);
        assertThat(sparkNode).containsEntry("max_retries", 2);
        assertThat(sparkNode).containsEntry("callback_base_url", "http://localhost:8080");
        assertThat((List<String>) sparkNode.get("upstream_task_keys"))
                .containsExactly("sync_user");
        assertThat((List<String>) sparkNode.get("from_tables")).containsExactly("onelake.ods.user");
        verify(paramResolver).resolveForTasks(
                tenantId, pipelineId, Set.of("sync_user", "spark_dwd"), null, Map.of());
        Map<String, Object> execution = (Map<String, Object>) config.opConfig().get("execution");
        assertThat(execution).isEqualTo(Map.of("config", Map.of("max_concurrent", 6)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void graphRunConfigCarriesTrinoSessionAndRendersSqlParameters() {
        UUID pipelineId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        PipelineTask trino = task(pipelineId, tenantId, "trino_validate", TaskType.TRINO_SQL, true, """
            {
              "sql": "select date '${bizdate}' as bizdate",
              "catalog": "ICEBERG",
              "schema": "DWD"
            }
            """);
        trino.setEngine("TRINO");
        trino.setTargetFqn(null);
        PipelineCompileResult compile = new PipelineCompileResult(
                pipelineId,
                "pipeline_" + pipelineId,
                tenantId,
                List.of(new PipelineCompileResult.TaskCompileResult(
                        trino.getId(), trino.getTaskKey(), trino.getTaskType().name(),
                        true, null, null)),
                true,
                List.of());
        TaskBundleContext context = new TaskBundleContext(
                pipelineId, tenantId, runId, compile,
                "pipeline_" + pipelineId, "spark-default", "spark-small");
        RunContext runContext = runContext("2026-07-11T16:00:00Z", "2026-07-12T16:00:00Z");

        DagsterRunConfig config = builder.buildGraphRunConfig(
                context, List.of(trino), List.of(), "", 1, Map.of(),
                runContext, runContext.builtInParameters(runId));

        Map<String, Object> ops = (Map<String, Object>) config.opConfig().get("ops");
        Map<String, Object> node = (Map<String, Object>)
                ((Map<String, Object>) ops.get("trino_validate")).get("config");
        assertThat(node)
                .containsEntry("task_type", "TRINO_SQL")
                .containsEntry("engine", "TRINO")
                .containsEntry("catalog", "iceberg")
                .containsEntry("schema", "dwd")
                .containsEntry("sql_or_script", "select date '2026-07-12' as bizdate");
    }

    @Test
    @SuppressWarnings("unchecked")
    void graphRunConfigFreezesScriptSandboxLimitsAndSafeEnvironment() {
        UUID pipelineId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        PipelineTask script = task(pipelineId, tenantId, "python_daily", TaskType.PYTHON, true, """
            {
              "script":"print('${bizdate}')",
              "timeout_seconds":20,
              "cpu_seconds":10,
              "cpu_cores":1,
              "memory_mb":128,
              "max_processes":4,
              "max_files":128,
              "file_max_bytes":65536,
              "stdout_max_bytes":8192,
              "stderr_max_bytes":4096,
              "env":{"BIZ_LABEL":"daily"},
              "network_allowlist":[]
            }
            """);
        script.setEngine("SCRIPT");
        script.setTargetFqn(null);
        PipelineCompileResult compile = new PipelineCompileResult(
                pipelineId, "pipeline_" + pipelineId, tenantId,
                List.of(new PipelineCompileResult.TaskCompileResult(
                        script.getId(), script.getTaskKey(), script.getTaskType().name(),
                        true, null, null)),
                true, List.of());
        TaskBundleContext context = new TaskBundleContext(
                pipelineId, tenantId, runId, compile,
                "pipeline_" + pipelineId, "spark-default", "spark-small");
        RunContext runContext = runContext("2026-07-11T16:00:00Z", "2026-07-12T16:00:00Z");

        DagsterRunConfig config = builder.buildGraphRunConfig(
                context, List.of(script), List.of(), "", 1, Map.of(),
                runContext, runContext.builtInParameters(runId));

        Map<String, Object> ops = (Map<String, Object>) config.opConfig().get("ops");
        Map<String, Object> node = (Map<String, Object>)
                ((Map<String, Object>) ops.get("python_daily")).get("config");
        assertThat(node)
                .containsEntry("task_type", "PYTHON")
                .containsEntry("engine", "SCRIPT")
                .containsEntry("sql_or_script", "print('2026-07-12')")
                .containsEntry("timeout_seconds", 20)
                .containsEntry("cpu_seconds", 10)
                .containsEntry("cpu_cores", 1)
                .containsEntry("memory_mb", 128)
                .containsEntry("max_processes", 4)
                .containsEntry("max_files", 128)
                .containsEntry("file_max_bytes", 65536)
                .containsEntry("stdout_max_bytes", 8192)
                .containsEntry("stderr_max_bytes", 4096)
                .containsEntry("network_allowlist", List.of());
        assertThat((List<Map<String, String>>) node.get("env"))
                .containsExactly(Map.of("key", "BIZ_LABEL", "value", "daily"));
    }

    @Test
    void legacyRunConfigRejectsTrinoInsteadOfSilentlyDroppingIt() {
        UUID pipelineId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        PipelineTask trino = task(
                pipelineId, tenantId, "trino_validate", TaskType.TRINO_SQL, true,
                "{\"sql\":\"SELECT 1\",\"catalog\":\"iceberg\",\"schema\":\"default\"}");
        PipelineCompileResult compile = new PipelineCompileResult(
                pipelineId, "pipeline_" + pipelineId, tenantId,
                List.of(new PipelineCompileResult.TaskCompileResult(
                        trino.getId(), trino.getTaskKey(), trino.getTaskType().name(),
                        true, null, null)),
                true, List.of());
        TaskBundleContext context = new TaskBundleContext(
                pipelineId, tenantId, UUID.randomUUID(), compile,
                "pipeline_" + pipelineId, "spark-default", "spark-small");

        assertThatThrownBy(() -> builder.build(
                context, List.of(trino), "", null, Map.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("GRAPH");
    }

    @Test
    @SuppressWarnings("unchecked")
    void graphRunConfigFreezesTransitiveUpstreamTaskKeys() {
        UUID pipelineId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        PipelineTask root = task(pipelineId, tenantId, "root", TaskType.SPARK_SQL, true, "{}");
        PipelineTask middle = task(pipelineId, tenantId, "middle", TaskType.SPARK_SQL, true, "{}");
        PipelineTask leaf = task(pipelineId, tenantId, "leaf", TaskType.SPARK_SQL, true, "{}");
        PipelineCompileResult compile = new PipelineCompileResult(
                pipelineId,
                "pipeline_" + pipelineId,
                tenantId,
                List.of(root, middle, leaf).stream()
                        .map(task -> new PipelineCompileResult.TaskCompileResult(
                                task.getId(), task.getTaskKey(), task.getTaskType().name(),
                                true, task.getTargetFqn(), null))
                        .toList(),
                true,
                List.of());
        TaskBundleContext context = new TaskBundleContext(
                pipelineId, tenantId, runId, compile,
                "pipeline_" + pipelineId, "spark-default", "spark-small");

        DagsterRunConfig config = builder.buildGraphRunConfig(
                context,
                List.of(root, middle, leaf),
                List.of(
                        edge("root", "middle", EdgeLayer.PIPELINE),
                        edge("middle", "leaf", EdgeLayer.PIPELINE)),
                "",
                2);

        Map<String, Object> ops = (Map<String, Object>) config.opConfig().get("ops");
        Map<String, Object> leafConfig = (Map<String, Object>)
                ((Map<String, Object>) ops.get("leaf")).get("config");
        assertThat((List<String>) leafConfig.get("upstream_task_keys"))
                .containsExactly("middle", "root");
    }

    @Test
    @SuppressWarnings("unchecked")
    void graphRunConfigFiltersSubgraphEdgesAndIncludesBaseAttempts() {
        UUID pipelineId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        PipelineTask root = task(pipelineId, tenantId, "root", TaskType.SPARK_SQL, true, "{}");
        PipelineTask failed = task(pipelineId, tenantId, "failed", TaskType.SPARK_SQL, true, "{}");
        PipelineTask downstream = task(pipelineId, tenantId, "downstream", TaskType.SPARK_SQL, true, "{}");
        PipelineCompileResult compile = new PipelineCompileResult(
                pipelineId,
                "pipeline_" + pipelineId,
                tenantId,
                List.of(root, failed, downstream).stream()
                        .map(task -> new PipelineCompileResult.TaskCompileResult(
                                task.getId(), task.getTaskKey(), task.getTaskType().name(),
                                true, task.getTargetFqn(), null))
                        .toList(),
                true,
                List.of());
        TaskBundleContext context = new TaskBundleContext(
                pipelineId, tenantId, runId, compile,
                "pipeline_" + pipelineId, "spark-default", "spark-small");

        DagsterRunConfig config = builder.buildGraphRunConfig(
                context,
                List.of(failed, downstream),
                List.of(
                        edge("root", "failed", EdgeLayer.PIPELINE),
                        edge("failed", "downstream", EdgeLayer.PIPELINE),
                        edge("downstream", "outside", EdgeLayer.PIPELINE)),
                "",
                4,
                Map.of("failed", 3, "downstream", 2));

        Map<String, Object> ops = (Map<String, Object>) config.opConfig().get("ops");
        assertThat(ops).containsOnlyKeys("failed", "downstream");
        Map<String, Object> failedOp = (Map<String, Object>) ops.get("failed");
        Map<String, Object> downstreamOp = (Map<String, Object>) ops.get("downstream");
        assertThat(((Map<String, Object>) failedOp.get("config"))).containsEntry("base_attempt", 3);
        assertThat(((Map<String, Object>) downstreamOp.get("config"))).containsEntry("base_attempt", 2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void rendersTaskScopedParamsAndKeepsBuiltInsAuthoritative() {
        UUID pipelineId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        PipelineTask task = task(pipelineId, tenantId, "daily", TaskType.SPARK_SQL, true, """
            {"sql":"select '${region}', '${run_id}', '${missing}'"}
            """);
        PipelineCompileResult compile = new PipelineCompileResult(
                pipelineId,
                "pipeline_" + pipelineId,
                tenantId,
                List.of(new PipelineCompileResult.TaskCompileResult(
                        task.getId(), task.getTaskKey(), task.getTaskType().name(),
                        true, task.getTargetFqn(), null)),
                true,
                List.of());
        TaskBundleContext context = new TaskBundleContext(
                pipelineId, tenantId, runId, compile,
                "pipeline_" + pipelineId, "spark-default", "spark-small");
        when(paramResolver.resolveForTasks(
                eq(tenantId), eq(pipelineId), anyCollection(), isNull(), anyMap()))
                .thenReturn(Map.of("daily", Map.of(
                        "region", "eu", "run_id", "user-value")));

        DagsterRunConfig config = builder.buildGraphRunConfig(
                context, List.of(task), List.of(), "", 1, Map.of(),
                Map.of("run_id", runId.toString()));

        Map<String, Object> ops = (Map<String, Object>) config.opConfig().get("ops");
        Map<String, Object> op = (Map<String, Object>) ops.get("daily");
        Map<String, Object> opConfig = (Map<String, Object>) op.get("config");
        assertThat(opConfig.get("sql_or_script"))
                .isEqualTo("select 'eu', '" + runId + "', '${missing}'");
        assertThat((List<Map<String, String>>) opConfig.get("runtime_params"))
                .containsExactlyInAnyOrder(
                        Map.of("key", "region", "value", "eu"),
                        Map.of("key", "run_id", "value", runId.toString()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void keepsDeferredUpstreamExpressionsOnlyWhereNodeSqlUsesThem() {
        UUID pipelineId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        PipelineTask extract = task(
                pipelineId, tenantId, "extract", TaskType.SPARK_SQL, true,
                "{\"sql\":\"select 1\"}");
        PipelineTask transform = task(
                pipelineId, tenantId, "transform", TaskType.SPARK_SQL, true,
                "{\"sql\":\"select '${row_count}'\"}");
        PipelineCompileResult compile = new PipelineCompileResult(
                pipelineId,
                "pipeline_" + pipelineId,
                tenantId,
                List.of(extract, transform).stream()
                        .map(task -> new PipelineCompileResult.TaskCompileResult(
                                task.getId(), task.getTaskKey(), task.getTaskType().name(),
                                true, task.getTargetFqn(), null))
                        .toList(),
                true,
                List.of());
        TaskBundleContext context = new TaskBundleContext(
                pipelineId, tenantId, runId, compile,
                "pipeline_" + pipelineId, "spark-default", "spark-small");
        Map<String, String> resolved = Map.of(
                "region", "cn",
                "row_count", "${upstream.extract.rowsWritten}");
        when(paramResolver.resolveForTasks(
                eq(tenantId), eq(pipelineId), anyCollection(), isNull(), anyMap()))
                .thenReturn(Map.of("extract", resolved, "transform", resolved));

        DagsterRunConfig config = builder.buildGraphRunConfig(
                context,
                List.of(extract, transform),
                List.of(edge("extract", "transform", EdgeLayer.PIPELINE)),
                "",
                1,
                Map.of(),
                Map.of());

        Map<String, Object> ops = (Map<String, Object>) config.opConfig().get("ops");
        Map<String, Object> extractConfig = (Map<String, Object>)
                ((Map<String, Object>) ops.get("extract")).get("config");
        Map<String, Object> transformConfig = (Map<String, Object>)
                ((Map<String, Object>) ops.get("transform")).get("config");
        assertThat(extractConfig.get("sql_or_script")).isEqualTo("select 1");
        assertThat(transformConfig.get("sql_or_script"))
                .isEqualTo("select '${upstream.extract.rowsWritten}'");
        assertThat((List<Map<String, String>>) extractConfig.get("runtime_params"))
                .containsExactly(Map.of("key", "region", "value", "cn"));
        assertThat((List<Map<String, String>>) transformConfig.get("runtime_params"))
                .containsExactly(Map.of("key", "region", "value", "cn"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void rendersDynamicTimeBeforeProducingSqlOrScript() {
        UUID pipelineId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        PipelineTask task = task(pipelineId, tenantId, "daily", TaskType.SPARK_SQL, true, """
            {"sql":"select '${bizdate}', '${bizdate-1:yyyyMMdd}', '${cyctime}'"}
            """);
        PipelineCompileResult compile = new PipelineCompileResult(
                pipelineId,
                "pipeline_" + pipelineId,
                tenantId,
                List.of(new PipelineCompileResult.TaskCompileResult(
                        task.getId(), task.getTaskKey(), task.getTaskType().name(),
                        true, task.getTargetFqn(), null)),
                true,
                List.of());
        TaskBundleContext bundle = new TaskBundleContext(
                pipelineId, tenantId, runId, compile,
                "pipeline_" + pipelineId, "spark-default", "spark-small");
        RunContext runContext = runContext("2026-06-30T16:00:00Z", "2026-07-01T16:00:00Z");

        DagsterRunConfig config = builder.buildGraphRunConfig(
                bundle, List.of(task), List.of(), "", 1, Map.of(),
                runContext, runContext.builtInParameters(runId));

        Map<String, Object> ops = (Map<String, Object>) config.opConfig().get("ops");
        Map<String, Object> op = (Map<String, Object>) ops.get("daily");
        Map<String, Object> opConfig = (Map<String, Object>) op.get("config");
        assertThat(opConfig.get("sql_or_script"))
                .isEqualTo("select '2026-07-01', '20260630', '2026-07-02T00:00:00+08:00'");
        verify(paramResolver).resolveForTasks(
                tenantId,
                pipelineId,
                Set.of("daily"),
                runContext,
                runContext.builtInParameters(runId));
    }

    @Test
    void rendersQualityGateParametersBeforeJsonEncoding() {
        UUID pipelineId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        PipelineTask gate = task(pipelineId, tenantId, "gate", TaskType.QUALITY_GATE, true, """
            {
              "targetModelFqn":"onelake.dwd.orders",
              "gates":[{
                "id":"daily","kind":"CUSTOM_SQL","enabled":true,
                "assertionSql":"select * from {{ model }} where dt = '${bizdate}' and region = '${region}'"
              }]
            }
            """);
        RunContext runContext = runContext("2026-06-30T16:00:00Z", "2026-07-01T16:00:00Z");

        Map<String, Object> config = builder.buildPerTaskOpConfig(
                gate, null, runContext, Map.of("region", "north\\west's"));

        assertThat(config.get("sql_or_script").toString())
                .contains("2026-07-01")
                .contains("north\\\\\\\\west's")
                .doesNotContain("${bizdate}")
                .doesNotContain("${region}");
    }

    private RunContext runContext(String logicalDate, String dataIntervalEnd) {
        Instant logical = Instant.parse(logicalDate);
        return new RunContext(
                logical,
                logical,
                Instant.parse(dataIntervalEnd),
                "Asia/Shanghai",
                "NORMAL",
                null,
                TriggerType.BACKFILL);
    }

    private PipelineTask task(UUID pipelineId, UUID tenantId, String key, TaskType type,
                              boolean executable, String config) {
        PipelineTask task = new PipelineTask();
        task.setId(UUID.randomUUID());
        task.setTenantId(tenantId);
        task.setDagId(pipelineId);
        task.setTaskKey(key);
        task.setTaskType(type);
        task.setName(key);
        task.setEngine("SPARK_SQL");
        task.setTargetFqn("onelake.dwd." + key);
        task.setExecutable(executable);
        task.setConfig(config);
        return task;
    }

    private PipelineTaskEdge edge(String sourceKey, String targetKey, EdgeLayer layer) {
        PipelineTaskEdge edge = new PipelineTaskEdge();
        edge.setSourceKey(sourceKey);
        edge.setTargetKey(targetKey);
        edge.setEdgeLayer(layer);
        return edge;
    }
}
