package com.onelake.orchestration.service.spi;

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
}
