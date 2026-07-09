package com.onelake.orchestration.service.spi;

import com.onelake.orchestration.domain.entity.PipelineTaskEdge;
import com.onelake.orchestration.domain.enums.EdgeLayer;
import com.onelake.orchestration.domain.entity.PipelineTask;
import com.onelake.orchestration.domain.enums.TaskType;
import com.onelake.orchestration.dto.PipelineCompileResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SparkRunConfigBuilderTest {

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

        DagsterRunConfig config = new SparkRunConfigBuilder().build(context, List.of(gate));

        Map<String, Object> ops = (Map<String, Object>) config.opConfig().get("ops");
        Map<String, Object> op = (Map<String, Object>) ops.get("run_spark_task_op");
        Map<String, Object> opConfig = (Map<String, Object>) op.get("config");
        List<Map<String, Object>> tasks = (List<Map<String, Object>>) opConfig.get("tasks");

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

        DagsterRunConfig config = new SparkRunConfigBuilder().buildGraphRunConfig(
                context,
                List.of(sync, spark),
                List.of(pipelineEdge, crossEngineEdge),
                "http://localhost:8080",
                6);

        assertThat(config.jobName()).isEqualTo("onelake_pipeline_graph_run");
        Map<String, Object> ops = (Map<String, Object>) config.opConfig().get("ops");
        Map<String, Object> op = (Map<String, Object>) ops.get("run_pipeline_graph_op");
        Map<String, Object> opConfig = (Map<String, Object>) op.get("config");
        assertThat(opConfig).containsEntry("execution_mode", "GRAPH");
        assertThat(opConfig).containsEntry("callback_base_url", "http://localhost:8080");
        assertThat(opConfig).containsEntry("max_parallel", 6);
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) opConfig.get("nodes");
        assertThat(nodes).hasSize(2);
        Map<String, Object> sparkNode = nodes.stream()
                .filter(node -> "spark_dwd".equals(node.get("task_key")))
                .findFirst()
                .orElseThrow();
        assertThat(sparkNode).containsEntry("task_type", "SPARK_SQL");
        assertThat(sparkNode).containsEntry("max_retries", 2);
        assertThat((List<String>) sparkNode.get("from_tables")).containsExactly("onelake.ods.user");
        List<Map<String, Object>> edges = (List<Map<String, Object>>) opConfig.get("edges");
        assertThat(edges).containsExactly(Map.of(
                "source_key", "sync_user",
                "target_key", "spark_dwd"));
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
