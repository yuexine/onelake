package com.onelake.orchestration.service.spi;

import com.onelake.orchestration.domain.entity.PipelineTask;
import com.onelake.orchestration.domain.enums.TaskType;
import com.onelake.orchestration.dto.PipelineCompileResult;
import com.onelake.orchestration.dto.PipelineCompileResult.TaskCompileResult;
import com.onelake.common.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P-Spark: builds Dagster runConfig for the {@code run_spark_task_op}.
 *
 * <p><b>C2</b> (§6.3.1): one op invocation receives the Spark task bundle. The unified
 * pipeline mainline is Spark-only; external model-run ordering is no longer part of this job.
 * <b>C5</b> (§6.4): resource_profile (executor_memory/cores/num_executors/driver_memory) is
 * honored as actual spark-submit flags — no longer just logged.
 * <b>C1</b>: reads only {@code pipeline_task.config} (Spark tasks have no model_id).
 * QUALITY_GATE tasks render to PySpark assertions and run in the same Spark task bundle.
 */
@Component
@Slf4j
public class SparkRunConfigBuilder implements EngineRunConfigBuilder {

    private static final String JOB_NAME = "onelake_pipeline_run";
    private static final String OP_NAME = "run_spark_task_op";

    @Override
    public EngineType engine() {
        return EngineType.SPARK_SQL;
    }

    @Override
    public DagsterRunConfig build(TaskBundleContext ctx) {
        return build(ctx, List.of());
    }

    public DagsterRunConfig build(TaskBundleContext ctx, List<PipelineTask> tasks) {
        PipelineCompileResult plan = ctx.compileResult();
        List<Map<String, Object>> sparkTasks = new ArrayList<>();
        Map<String, PipelineTask> taskByKey = tasks.stream()
                .collect(java.util.stream.Collectors.toMap(PipelineTask::getTaskKey, t -> t, (a, b) -> a));
        for (TaskCompileResult result : plan.tasks()) {
            if (result.taskType() == null || !isSparkRuntimeType(result.taskType())) continue;
            PipelineTask task = taskByKey.get(result.taskKey());
            if (task == null || !Boolean.TRUE.equals(task.getExecutable())) continue;
            sparkTasks.add(buildPerTaskOpConfig(task, null));
        }

        Map<String, Object> opConfig = new LinkedHashMap<>();
        opConfig.put("pipeline_id", ctx.pipelineId().toString());
        opConfig.put("run_id", ctx.runId().toString());
        opConfig.put("tenant_id", ctx.tenantId().toString());
        opConfig.put("iceberg_catalog", "onelake");
        opConfig.put("tasks", sparkTasks);
        opConfig.put("resource_profile", defaultSparkProfile());
        return new DagsterRunConfig(JOB_NAME, Map.of(
                "ops", Map.of(OP_NAME, Map.of("config", opConfig))
        ));
    }

    /**
     * Build per-task op config for one Spark task (used by OrchestrationService when launching).
     */
    public Map<String, Object> buildPerTaskOpConfig(PipelineTask task, Map<String, Object> resourceProfile) {
        Map<String, Object> opConfig = new LinkedHashMap<>();
        opConfig.put("task_key", task.getTaskKey());
        String taskType = task.getTaskType().name();
        opConfig.put("task_type", taskType);
        opConfig.put("target_fqn", task.getTargetFqn() == null ? "" : task.getTargetFqn());

        // Parse config jsonb to extract script/sql/from_tables
        JsonNode cfg = parseSafe(task.getConfig());
        if (TaskType.QUALITY_GATE.name().equals(taskType)) {
            opConfig.put("sql_or_script", QualityGateScriptRenderer.render(task));
            String target = QualityGateScriptRenderer.targetFqn(task, cfg);
            opConfig.put("from_tables", StringUtils.hasText(target) ? List.of(target) : List.of());
        } else {
            String sql = textOrEmpty(cfg, "sql");
            String script = textOrEmpty(cfg, "script");
            opConfig.put("sql_or_script", StringUtils.hasText(script) ? script : sql);
            opConfig.put("from_tables", textArray(cfg.path("from_tables")));
        }

        Map<String, Object> profile = resourceProfile != null ? resourceProfile : resourceProfile(cfg);
        opConfig.put("resource_profile", profile);

        return opConfig;
    }

    private static Map<String, Object> resourceProfile(JsonNode cfg) {
        JsonNode node = cfg == null ? null : cfg.path("resource_profile");
        if (node == null || !node.isObject()) {
            return defaultSparkProfile();
        }
        Map<String, Object> profile = defaultSparkProfile();
        putIfText(node, profile, "executor_memory");
        putIfText(node, profile, "executor_cores");
        putIfText(node, profile, "num_executors");
        putIfText(node, profile, "driver_memory");
        return profile;
    }

    private static Map<String, Object> defaultSparkProfile() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("executor_memory", "2g");
        p.put("executor_cores", "2");
        p.put("num_executors", "2");
        p.put("driver_memory", "1g");
        return p;
    }

    private static boolean isSparkRuntimeType(String taskType) {
        return TaskType.SPARK_SQL.name().equals(taskType)
                || TaskType.PYSPARK.name().equals(taskType)
                || TaskType.QUALITY_GATE.name().equals(taskType);
    }

    private static JsonNode parseSafe(String json) {
        if (json == null || json.isBlank() || "{}".equals(json.trim())) {
            return JsonUtil.mapper().nullNode();
        }
        try {
            return JsonUtil.mapper().readTree(json);
        } catch (Exception e) {
            log.warn("SparkRunConfigBuilder: failed to parse config: {}", e.getMessage());
            return JsonUtil.mapper().nullNode();
        }
    }

    private static String textOrEmpty(JsonNode node, String field) {
        if (node == null) return "";
        JsonNode v = node.path(field);
        return v.isTextual() ? v.asText("") : "";
    }

    private static List<String> textArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        node.forEach(item -> {
            String value = item.asText("");
            if (StringUtils.hasText(value)) {
                values.add(value);
            }
        });
        return values;
    }

    private static void putIfText(JsonNode node, Map<String, Object> profile, String field) {
        String value = textOrEmpty(node, field);
        if (StringUtils.hasText(value)) {
            profile.put(field, value);
        }
    }
}
