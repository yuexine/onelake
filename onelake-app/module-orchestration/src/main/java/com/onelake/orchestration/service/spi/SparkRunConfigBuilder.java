package com.onelake.orchestration.service.spi;

import com.onelake.orchestration.domain.entity.PipelineTask;
import com.onelake.orchestration.domain.entity.PipelineTaskEdge;
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
 * P-Spark：为 {@code run_spark_task_op} 构建 Dagster runConfig。
 *
 * <p>单次 op 调用接收一批 Spark 节点。统一流水线主路径以 Spark 为准，
 * 外部模型运行顺序不再进入该作业。
 *
 * <p>{@code resource_profile}（executor_memory/cores/num_executors/driver_memory）
 * 会作为真实 spark-submit 参数生效，而不只是记录日志。
 *
 * <p>构建器只读取 {@code pipeline_task.config}；{@code QUALITY_GATE}
 * 会渲染成 PySpark 断言脚本，并放入同一个 Spark 节点包中执行。
 */
@Component
@Slf4j
public class SparkRunConfigBuilder implements EngineRunConfigBuilder {

    private static final String JOB_NAME = "onelake_pipeline_run";
    private static final String OP_NAME = "run_spark_task_op";
    private static final String GRAPH_JOB_PREFIX = "onelake_pipeline_graph_";

    @Override
    public EngineType engine() {
        return EngineType.SPARK_SQL;
    }

    @Override
    public DagsterRunConfig build(TaskBundleContext ctx) {
        return build(ctx, List.of());
    }

    public DagsterRunConfig build(TaskBundleContext ctx, List<PipelineTask> tasks) {
        return build(ctx, tasks, "");
    }

    public DagsterRunConfig build(TaskBundleContext ctx, List<PipelineTask> tasks, String callbackBaseUrl) {
        return build(ctx, tasks, callbackBaseUrl, Map.of());
    }

    public DagsterRunConfig build(TaskBundleContext ctx,
                                  List<PipelineTask> tasks,
                                  String callbackBaseUrl,
                                  Map<String, String> runtimeParams) {
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
        opConfig.put("callback_base_url", callbackBaseUrl == null ? "" : callbackBaseUrl);
        opConfig.put("runtime_params", runtimeParamEntries(runtimeParams));
        opConfig.put("tasks", sparkTasks);
        opConfig.put("resource_profile", defaultSparkProfile());
        return new DagsterRunConfig(JOB_NAME, Map.of(
                "ops", Map.of(OP_NAME, Map.of("config", opConfig))
        ));
    }

    public DagsterRunConfig buildGraphRunConfig(TaskBundleContext ctx,
                                                List<PipelineTask> tasks,
                                                List<PipelineTaskEdge> pipelineEdges,
                                                String callbackBaseUrl,
                                                int maxParallel) {
        return buildGraphRunConfig(ctx, tasks, pipelineEdges, callbackBaseUrl, maxParallel, Map.of());
    }

    public DagsterRunConfig buildGraphRunConfig(TaskBundleContext ctx,
                                                List<PipelineTask> tasks,
                                                List<PipelineTaskEdge> pipelineEdges,
                                                String callbackBaseUrl,
                                                int maxParallel,
                                                Map<String, Integer> baseAttempts) {
        return buildGraphRunConfig(ctx, tasks, pipelineEdges, callbackBaseUrl, maxParallel, baseAttempts, Map.of());
    }

    public DagsterRunConfig buildGraphRunConfig(TaskBundleContext ctx,
                                                List<PipelineTask> tasks,
                                                List<PipelineTaskEdge> pipelineEdges,
                                                String callbackBaseUrl,
                                                int maxParallel,
                                                Map<String, Integer> baseAttempts,
                                                Map<String, String> runtimeParams) {
        // GRAPH 模式把可观测节点和 PIPELINE 边完整交给 Dagster op 内置调度器，旧 build(...) 保持扁平 tasks[] 回退。
        PipelineCompileResult plan = ctx.compileResult();
        Map<String, PipelineTask> taskByKey = tasks.stream()
                .collect(java.util.stream.Collectors.toMap(PipelineTask::getTaskKey, t -> t, (a, b) -> a,
                        LinkedHashMap::new));
        // base_attempt 只在重跑子图时由服务层传入；普通触发默认 1，保持旧 GRAPH 行为不变。
        Map<String, Integer> safeBaseAttempts = baseAttempts == null ? Map.of() : baseAttempts;
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (TaskCompileResult result : plan.tasks()) {
            PipelineTask task = taskByKey.get(result.taskKey());
            if (task == null) {
                continue;
            }
            Map<String, Object> node = new LinkedHashMap<>(buildPerTaskOpConfig(task, null));
            node.put("base_attempt", Math.max(1, safeBaseAttempts.getOrDefault(task.getTaskKey(), 1)));
            node.put("max_retries", resolveMaxRetries(task));
            nodes.add(node);
        }

        // GRAPH job 在 Dagster code location 重载时按 pipelineId 生成真实 op 图。
        // 每个 task_key 都是一个 op 名，从而 Dagster step_key 与 task_key 一致；
        // Java 仍然是每个 op 的 runConfig 唯一构造方。
        Map<String, Object> ops = new LinkedHashMap<>();
        for (Map<String, Object> node : nodes) {
            String taskKey = String.valueOf(node.get("task_key"));
            Map<String, Object> opConfig = new LinkedHashMap<>(node);
            opConfig.put("pipeline_id", ctx.pipelineId().toString());
            opConfig.put("run_id", ctx.runId().toString());
            opConfig.put("tenant_id", ctx.tenantId().toString());
            opConfig.put("iceberg_catalog", "onelake");
            opConfig.put("callback_base_url", callbackBaseUrl == null ? "" : callbackBaseUrl);
            opConfig.put("runtime_params", runtimeParamEntries(runtimeParams));
            ops.put(taskKey, Map.of("config", opConfig));
        }
        return new DagsterRunConfig(graphJobName(ctx.pipelineId()), Map.of(
                "execution", Map.of("config", Map.of(
                        "max_concurrent", Math.max(1, maxParallel))),
                "ops", ops
        ));
    }

    public static String graphJobName(java.util.UUID pipelineId) {
        return GRAPH_JOB_PREFIX + pipelineId.toString().replace("-", "");
    }

    /**
     * 为单个 Spark 节点构建 op 配置，供 {@code OrchestrationService} 触发时使用。
     */
    public Map<String, Object> buildPerTaskOpConfig(PipelineTask task, Map<String, Object> resourceProfile) {
        Map<String, Object> opConfig = new LinkedHashMap<>();
        opConfig.put("task_key", task.getTaskKey());
        String taskType = task.getTaskType().name();
        opConfig.put("task_type", taskType);
        opConfig.put("target_fqn", task.getTargetFqn() == null ? "" : task.getTargetFqn());

        // 解析 config jsonb，提取 script/sql/from_tables。
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

    private static List<Map<String, String>> runtimeParamEntries(Map<String, String> runtimeParams) {
        if (runtimeParams == null || runtimeParams.isEmpty()) {
            return List.of();
        }
        List<Map<String, String>> entries = new ArrayList<>();
        runtimeParams.forEach((key, value) -> {
            if (StringUtils.hasText(key) && value != null) {
                entries.add(Map.of("key", key, "value", value));
            }
        });
        return entries;
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

    private static int resolveMaxRetries(PipelineTask task) {
        // 节点级重试参数先从任务 config 读取；不存在时保持 0，兼容旧流水线定义。
        JsonNode cfg = parseSafe(task.getConfig());
        int retries = intField(cfg, "max_retries");
        if (retries < 0) {
            retries = intField(cfg, "maxRetries");
        }
        return Math.max(0, retries);
    }

    private static int intField(JsonNode node, String field) {
        if (node == null) {
            return -1;
        }
        JsonNode value = node.path(field);
        if (value.isInt() || value.isLong()) {
            return value.asInt(-1);
        }
        if (value.isTextual()) {
            try {
                return Integer.parseInt(value.asText());
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return -1;
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
            log.warn("SparkRunConfigBuilder：解析 config 失败：{}", e.getMessage());
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
