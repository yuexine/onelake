package com.onelake.orchestration.service.spi;

import com.onelake.orchestration.domain.entity.PipelineTask;
import com.onelake.orchestration.domain.entity.PipelineTaskEdge;
import com.onelake.orchestration.domain.enums.EdgeLayer;
import com.onelake.orchestration.domain.enums.TaskType;
import com.onelake.orchestration.dto.PipelineCompileResult;
import com.onelake.orchestration.dto.PipelineCompileResult.TaskCompileResult;
import com.onelake.orchestration.service.ParamRenderer;
import com.onelake.orchestration.service.ParamResolver;
import com.onelake.orchestration.service.RunContext;
import com.onelake.common.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
@RequiredArgsConstructor
@Slf4j
public class SparkRunConfigBuilder implements EngineRunConfigBuilder {

    private static final String JOB_NAME = "onelake_pipeline_run";
    private static final String OP_NAME = "run_spark_task_op";
    private static final String GRAPH_JOB_PREFIX = "onelake_pipeline_graph_";

    private final ParamResolver paramResolver;

    /** 声明该构建器处理 Spark 家族节点。 */
    @Override
    public EngineType engine() {
        return EngineType.SPARK_SQL;
    }

    /** 构建不含显式任务定义的兼容配置。 */
    @Override
    public DagsterRunConfig build(TaskBundleContext ctx) {
        return build(ctx, List.of());
    }

    /** 构建 LEGACY 固定 op 配置，不启用节点状态回调。 */
    public DagsterRunConfig build(TaskBundleContext ctx, List<PipelineTask> tasks) {
        return build(ctx, tasks, "");
    }

    /** 构建 LEGACY 固定 op 配置，并把回调根地址传入 Spark 执行器。 */
    public DagsterRunConfig build(TaskBundleContext ctx, List<PipelineTask> tasks, String callbackBaseUrl) {
        return build(ctx, tasks, callbackBaseUrl, Map.of());
    }

    /**
     * 构建 LEGACY 模式 runConfig。
     *
     * <p>所有可执行 Spark 节点被装入固定 {@code run_spark_task_op} 的 tasks 数组；
     * logical date 和数据区间等运行参数通过 {@code runtime_params} 注入。</p>
     */
    public DagsterRunConfig build(TaskBundleContext ctx,
                                  List<PipelineTask> tasks,
                                  String callbackBaseUrl,
                                  Map<String, String> runtimeParams) {
        return build(ctx, tasks, callbackBaseUrl, null, runtimeParams);
    }

    /** 构建 LEGACY 配置，并用运行上下文渲染动态业务时间参数。 */
    public DagsterRunConfig build(TaskBundleContext ctx,
                                  List<PipelineTask> tasks,
                                  String callbackBaseUrl,
                                  RunContext runContext,
                                  Map<String, String> runtimeParams) {
        return build(ctx, tasks, callbackBaseUrl, runContext, runtimeParams, List.of());
    }

    /** 构建 LEGACY 配置，并冻结本次运行用于节点间传参的 PIPELINE 祖先集合。 */
    public DagsterRunConfig build(TaskBundleContext ctx,
                                  List<PipelineTask> tasks,
                                  String callbackBaseUrl,
                                  RunContext runContext,
                                  Map<String, String> runtimeParams,
                                  List<PipelineTaskEdge> pipelineEdges) {
        PipelineCompileResult plan = ctx.compileResult();
        List<Map<String, Object>> sparkTasks = new ArrayList<>();
        Map<String, PipelineTask> taskByKey = tasks.stream()
                .collect(java.util.stream.Collectors.toMap(PipelineTask::getTaskKey, t -> t, (a, b) -> a));
        Map<String, Map<String, String>> userParamsByTask = resolveUserParameters(
                ctx, taskByKey.keySet(), runContext, runtimeParams);
        Map<String, List<String>> upstreamTaskKeys = frozenUpstreamTaskKeys(
                plan.tasks().stream().map(TaskCompileResult::taskKey).toList(), pipelineEdges);
        for (TaskCompileResult result : plan.tasks()) {
            if (result.taskType() == null || !isSparkRuntimeType(result.taskType())) continue;
            PipelineTask task = taskByKey.get(result.taskKey());
            if (task == null || !Boolean.TRUE.equals(task.getExecutable())) continue;
            Map<String, Object> node = new LinkedHashMap<>(buildPerTaskOpConfig(
                    task, null, runContext,
                    resolveTaskParameters(task, runtimeParams, userParamsByTask)));
            node.put("upstream_task_keys", upstreamTaskKeys.getOrDefault(task.getTaskKey(), List.of()));
            sparkTasks.add(node);
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

    /** 构建普通 GRAPH 运行配置，所有节点的累计 attempt 从 1 开始。 */
    public DagsterRunConfig buildGraphRunConfig(TaskBundleContext ctx,
                                                List<PipelineTask> tasks,
                                                List<PipelineTaskEdge> pipelineEdges,
                                                String callbackBaseUrl,
                                                int maxParallel) {
        return buildGraphRunConfig(ctx, tasks, pipelineEdges, callbackBaseUrl, maxParallel, Map.of());
    }

    /** 构建 GRAPH 重跑配置，并为选中节点注入跨运行累计的 base attempt。 */
    public DagsterRunConfig buildGraphRunConfig(TaskBundleContext ctx,
                                                List<PipelineTask> tasks,
                                                List<PipelineTaskEdge> pipelineEdges,
                                                String callbackBaseUrl,
                                                int maxParallel,
                                                Map<String, Integer> baseAttempts) {
        return buildGraphRunConfig(ctx, tasks, pipelineEdges, callbackBaseUrl, maxParallel, baseAttempts, Map.of());
    }

    /**
     * 构建 GRAPH 模式的完整 Dagster runConfig。
     *
     * <p>每个 {@link PipelineTask#getTaskKey() taskKey} 直接作为 Dagster op 配置键，
     * 因而运行时的 {@code dagster_step_key == pipeline_task.task_key}。Java 只提供节点配置、
     * 并发上限和运行参数，依赖顺序由 Dagster 中按 pipelineId 生成的原生图执行。</p>
     */
    public DagsterRunConfig buildGraphRunConfig(TaskBundleContext ctx,
                                                List<PipelineTask> tasks,
                                                List<PipelineTaskEdge> pipelineEdges,
                                                String callbackBaseUrl,
                                                int maxParallel,
                                                Map<String, Integer> baseAttempts,
                                                Map<String, String> runtimeParams) {
        return buildGraphRunConfig(
                ctx, tasks, pipelineEdges, callbackBaseUrl, maxParallel,
                baseAttempts, null, runtimeParams);
    }

    /** 构建 GRAPH 配置，并用运行上下文渲染动态业务时间参数。 */
    public DagsterRunConfig buildGraphRunConfig(TaskBundleContext ctx,
                                                List<PipelineTask> tasks,
                                                List<PipelineTaskEdge> pipelineEdges,
                                                String callbackBaseUrl,
                                                int maxParallel,
                                                Map<String, Integer> baseAttempts,
                                                RunContext runContext,
                                                Map<String, String> runtimeParams) {
        // GRAPH 模式把可观测节点和 PIPELINE 边完整交给 Dagster op 内置调度器，旧 build(...) 保持扁平 tasks[] 回退。
        PipelineCompileResult plan = ctx.compileResult();
        Map<String, PipelineTask> taskByKey = tasks.stream()
                .collect(java.util.stream.Collectors.toMap(PipelineTask::getTaskKey, t -> t, (a, b) -> a,
                        LinkedHashMap::new));
        // base_attempt 只在重跑子图时由服务层传入；普通触发默认 1，保持旧 GRAPH 行为不变。
        Map<String, Integer> safeBaseAttempts = baseAttempts == null ? Map.of() : baseAttempts;
        Map<String, Map<String, String>> userParamsByTask = resolveUserParameters(
                ctx, taskByKey.keySet(), runContext, runtimeParams);
        Map<String, Map<String, String>> paramsByTaskKey = new LinkedHashMap<>();
        Map<String, List<String>> upstreamTaskKeys = frozenUpstreamTaskKeys(
                plan.tasks().stream().map(TaskCompileResult::taskKey).toList(), pipelineEdges);
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (TaskCompileResult result : plan.tasks()) {
            PipelineTask task = taskByKey.get(result.taskKey());
            if (task == null) {
                continue;
            }
            Map<String, String> taskParams = resolveTaskParameters(
                    task, runtimeParams, userParamsByTask);
            paramsByTaskKey.put(task.getTaskKey(), taskParams);
            Map<String, Object> node = new LinkedHashMap<>(
                    buildPerTaskOpConfig(task, null, runContext, taskParams));
            node.put("base_attempt", Math.max(1, safeBaseAttempts.getOrDefault(task.getTaskKey(), 1)));
            node.put("max_retries", resolveMaxRetries(task));
            node.put("upstream_task_keys", upstreamTaskKeys.getOrDefault(task.getTaskKey(), List.of()));
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
            opConfig.put("runtime_params", runtimeParamEntries(
                    paramsByTaskKey.getOrDefault(taskKey, runtimeParams == null ? Map.of() : runtimeParams)));
            ops.put(taskKey, Map.of("config", opConfig));
        }
        return new DagsterRunConfig(graphJobName(ctx.pipelineId()), Map.of(
                "execution", Map.of("config", Map.of(
                        "max_concurrent", Math.max(1, maxParallel))),
                "ops", ops
        ));
    }

    /**
     * 返回流水线专属的稳定 Dagster graph job 名；移除 UUID 连字符以满足定义命名规则。
     */
    public static String graphJobName(java.util.UUID pipelineId) {
        return GRAPH_JOB_PREFIX + pipelineId.toString().replace("-", "");
    }

    /**
     * 为单个 Spark 节点构建 op 配置，供 {@code OrchestrationService} 触发时使用。
     *
     * <p>QUALITY_GATE 会先渲染为 PySpark 断言脚本；其他节点直接读取 config 中的
     * script/sql。调用方传入的资源配置优先于节点内配置。</p>
     */
    public Map<String, Object> buildPerTaskOpConfig(PipelineTask task, Map<String, Object> resourceProfile) {
        return buildPerTaskOpConfig(task, resourceProfile, Map.of());
    }

    /** H1 兼容入口；无运行上下文时只渲染普通键值参数。 */
    public Map<String, Object> buildPerTaskOpConfig(PipelineTask task,
                                                    Map<String, Object> resourceProfile,
                                                    Map<String, String> params) {
        return buildPerTaskOpConfig(task, resourceProfile, null, params);
    }

    /** 为单个节点生成配置，并在输出 sql_or_script 前完成 H2 参数渲染。 */
    public Map<String, Object> buildPerTaskOpConfig(PipelineTask task,
                                                    Map<String, Object> resourceProfile,
                                                    RunContext runContext,
                                                    Map<String, String> params) {
        Map<String, Object> opConfig = new LinkedHashMap<>();
        opConfig.put("task_key", task.getTaskKey());
        String taskType = task.getTaskType().name();
        opConfig.put("task_type", taskType);
        opConfig.put("target_fqn", task.getTargetFqn() == null ? "" : task.getTargetFqn());

        // 解析 config jsonb，提取 script/sql/from_tables。
        JsonNode cfg = parseSafe(task.getConfig());
        if (TaskType.QUALITY_GATE.name().equals(taskType)) {
            opConfig.put("sql_or_script", QualityGateScriptRenderer.render(task, runContext, params));
            String target = ParamRenderer.render(
                    QualityGateScriptRenderer.targetFqn(task, cfg), runContext, params);
            opConfig.put("from_tables", StringUtils.hasText(target) ? List.of(target) : List.of());
        } else {
            String sql = textOrEmpty(cfg, "sql");
            String script = textOrEmpty(cfg, "script");
            opConfig.put("sql_or_script", ParamRenderer.render(
                    StringUtils.hasText(script) ? script : sql, runContext, params));
            opConfig.put("from_tables", textArray(cfg.path("from_tables")));
        }

        Map<String, Object> profile = resourceProfile != null ? resourceProfile : resourceProfile(cfg);
        opConfig.put("resource_profile", profile);

        return opConfig;
    }

    private Map<String, Map<String, String>> resolveUserParameters(
            TaskBundleContext ctx,
            java.util.Collection<String> taskKeys,
            RunContext runContext,
            Map<String, String> builtInParams) {
        Map<String, Map<String, String>> resolved = paramResolver.resolveForTasks(
                ctx.tenantId(), ctx.pipelineId(), taskKeys, runContext,
                builtInParams == null ? Map.of() : builtInParams);
        return resolved == null ? Map.of() : resolved;
    }

    private Map<String, String> resolveTaskParameters(
            PipelineTask task,
            Map<String, String> builtInParams,
            Map<String, Map<String, String>> userParamsByTask) {
        Map<String, String> userParams = userParamsByTask.getOrDefault(
                task.getTaskKey(), Map.of());
        return RunContext.mergeWithBuiltIns(userParams, builtInParams);
    }

    private static List<Map<String, String>> runtimeParamEntries(Map<String, String> runtimeParams) {
        if (runtimeParams == null || runtimeParams.isEmpty()) {
            return List.of();
        }
        List<Map<String, String>> entries = new ArrayList<>();
        runtimeParams.forEach((key, value) -> {
            // 已被 SQL/脚本实际引用的上游表达式会在 Java 一次渲染后留在 sql_or_script，
            // 由节点启动前的 render-config 解析；不要把未使用的表达式放进整份 op config，
            // 否则 Dagster 会误判该节点引用了对应上游并触发无关的祖先校验。
            if (StringUtils.hasText(key)
                    && value != null
                    && !value.contains("${upstream.")) {
                entries.add(Map.of("key", key, "value", value));
            }
        });
        return entries;
    }

    private static Map<String, List<String>> frozenUpstreamTaskKeys(
            Collection<String> taskKeys,
            List<PipelineTaskEdge> pipelineEdges) {
        Map<String, Set<String>> parentsByTask = new LinkedHashMap<>();
        for (PipelineTaskEdge edge : pipelineEdges == null ? List.<PipelineTaskEdge>of() : pipelineEdges) {
            if (edge.getEdgeLayer() == EdgeLayer.PIPELINE) {
                parentsByTask.computeIfAbsent(edge.getTargetKey(), ignored -> new LinkedHashSet<>())
                        .add(edge.getSourceKey());
            }
        }

        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String taskKey : taskKeys) {
            Set<String> ancestors = new LinkedHashSet<>();
            Deque<String> pending = new ArrayDeque<>(
                    parentsByTask.getOrDefault(taskKey, Set.of()));
            while (!pending.isEmpty()) {
                String parent = pending.removeFirst();
                if (ancestors.add(parent)) {
                    pending.addAll(parentsByTask.getOrDefault(parent, Set.of()));
                }
            }
            result.put(taskKey, List.copyOf(ancestors));
        }
        return result;
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
