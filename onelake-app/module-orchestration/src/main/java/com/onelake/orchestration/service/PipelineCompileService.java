package com.onelake.orchestration.service;

import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.util.JsonUtil;
import com.onelake.orchestration.domain.entity.Dag;
import com.onelake.orchestration.domain.entity.PipelineTask;
import com.onelake.orchestration.domain.entity.PipelineTaskEdge;
import com.onelake.orchestration.domain.enums.EdgeLayer;
import com.onelake.orchestration.domain.enums.TaskCategory;
import com.onelake.orchestration.domain.enums.TaskCompileStatus;
import com.onelake.orchestration.domain.enums.TaskType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.onelake.orchestration.dto.PipelineCompileResult;
import com.onelake.orchestration.dto.PipelineCompileResult.TaskCompileResult;
import com.onelake.orchestration.repository.DagRepository;
import com.onelake.orchestration.repository.PipelineTaskEdgeRepository;
import com.onelake.orchestration.repository.PipelineTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 将流水线（DAG + 节点）编译为 {@link PipelineCompileResult}。
 *
 * <p><b>单一事实来源</b>：本服务只读取编排模块的流水线节点与边契约，
 * 不写入 {@code modeling.*} schema；统一流水线主路径以 Spark 为准。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PipelineCompileService {

    private final DagRepository dagRepo;
    private final PipelineTaskRepository taskRepo;
    private final PipelineTaskEdgeRepository edgeRepo;

    /**
     * 编译单条流水线，并在调用方事务内回写每个节点的
     * {@code compileStatus}、{@code executable} 和 {@code compileError}。
     */
    @Transactional
    public PipelineCompileResult compile(UUID dagId) {
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new BizException(40100, "Tenant context required to compile pipeline");
        }
        Dag dag = dagRepo.findByIdAndTenantId(dagId, tenantId)
                .orElseThrow(() -> new BizException(40400, "Pipeline 不存在"));

        List<PipelineTask> tasks = taskRepo.findByDagIdOrderByCreatedAtAsc(dagId);
        List<PipelineTaskEdge> edges = new ArrayList<>(edgeRepo.findByDagId(dagId));

        PipelineCompileResult result = compile(dagId, tenantId, tasks, edges);

        // 实时草稿编译才回写节点编译状态；快照编译使用同一纯集合入口但不访问数据库。
        taskRepo.saveAll(tasks);
        return result;
    }

    /**
     * 只基于调用方给定的任务和边编译，不读取或写入数据库。
     *
     * <p>发布版本运行从不可变 snapshot 重建实体集合后调用此入口，避免运行时重新读取
     * 已被编辑的实时 DAG 定义。</p>
     */
    public PipelineCompileResult compile(UUID dagId,
                                         UUID tenantId,
                                         List<PipelineTask> inputTasks,
                                         List<PipelineTaskEdge> inputEdges) {
        if (dagId == null || tenantId == null) {
            throw new IllegalArgumentException("dagId and tenantId must not be null");
        }
        List<PipelineTask> tasks = inputTasks == null ? new ArrayList<>() : new ArrayList<>(inputTasks);
        List<PipelineTaskEdge> edges = inputEdges == null ? new ArrayList<>() : new ArrayList<>(inputEdges);

        // 数据流边是下游输入的事实来源。Spark 节点从入边资产推导输入表，
        // 用户只需要在画布连线一次，不必在每个节点 config 中重复维护 from_tables。
        applyDataflowInputs(tasks, edges);

        // 1. 节点级校验。
        Map<UUID, TaskCompileResult> resultsById = new LinkedHashMap<>();
        Map<String, PipelineTask> taskByKey = new HashMap<>();
        for (PipelineTask t : tasks) {
            taskByKey.put(t.getTaskKey(), t);
            TaskCompileResult r = validateTask(t, tenantId);
            resultsById.put(t.getId(), r);
            applyCompileResult(t, r);
        }

        // 2. 图级校验：环路、悬空边、反向跨引擎边等。
        List<String> graphErrors = new ArrayList<>(validateGraph(tasks, edges, taskByKey));
        graphErrors.addAll(validateUpstreamReferences(tasks, edges));

        // 3. 按 PIPELINE 边做拓扑排序。
        List<PipelineTask> ordered;
        try {
            ordered = topologicalSort(tasks, edges);
        } catch (IllegalStateException cycle) {
            graphErrors.add("Cycle detected: " + cycle.getMessage());
            ordered = tasks;
        }

        boolean allValidated = graphErrors.isEmpty()
                && resultsById.values().stream().allMatch(TaskCompileResult::valid);

        String pipelineTag = "pipeline_" + dagId;
        return new PipelineCompileResult(
                dagId,
                pipelineTag,
                tenantId,
                ordered.stream().map(t -> {
                    TaskCompileResult r = resultsById.get(t.getId());
                    return new TaskCompileResult(
                            t.getId(), t.getTaskKey(), t.getTaskType().name(),
                            r.valid(), r.targetFqn(), r.errorMessage());
                }).toList(),
                allValidated,
                graphErrors
        );
    }

    /** 按节点类型分派最小运行契约校验。 */
    private TaskCompileResult validateTask(PipelineTask t, UUID tenantId) {
        TaskType type = t.getTaskType();
        t.setCategory(type.category());
        return switch (type) {
            case QUALITY_GATE -> validateQualityGate(t);
            case SYNC_REF -> validateSyncRef(t);
            case SPARK_SQL, PYSPARK -> validateSparkTask(t);
            case TRINO_SQL, PYTHON, SHELL, BRANCH, CONDITION, SENSOR, WAIT,
                    SUB_PIPELINE, NOTIFY, ASSERTION -> validateExtensionTask(t);
        };
    }

    /** M4 后续步骤会补充类型专属规则；基线阶段只要求存在结构化配置。 */
    private TaskCompileResult validateExtensionTask(PipelineTask t) {
        if (!StringUtils.hasText(t.getConfig()) || "{}".equals(t.getConfig().trim())) {
            return fail(t, t.getTaskType().name() + " task requires non-empty config");
        }
        JsonNode cfg = parseSafeJson(t.getConfig());
        if (cfg == null || !cfg.isObject()) {
            return fail(t, t.getTaskType().name() + " task requires valid object config");
        }
        try {
            validateParamExpressions(cfg);
        } catch (IllegalArgumentException ex) {
            return fail(t, t.getTaskType().name() + " parameter expression invalid: " + ex.getMessage());
        }
        return ok(t);
    }

    /** 校验质量门禁规则集合和目标资产。 */
    private TaskCompileResult validateQualityGate(PipelineTask t) {
        if (!StringUtils.hasText(t.getConfig()) || "{}".equals(t.getConfig().trim())) {
            return fail(t, "QUALITY_GATE task requires non-empty config (rule definitions)");
        }
        JsonNode cfg = parseSafeJson(t.getConfig());
        String target = firstText(
                textOrEmpty(cfg, "targetModelFqn"),
                textOrEmpty(cfg, "target_model_fqn"),
                t.getTargetFqn());
        if (!StringUtils.hasText(target)) {
            return fail(t, "QUALITY_GATE task requires targetFqn or config.targetModelFqn");
        }
        JsonNode gates = cfg.path("gates");
        if (!gates.isArray() || gates.size() == 0) {
            return fail(t, "QUALITY_GATE task requires config.gates with at least one rule");
        }
        try {
            validateParamExpressions(cfg);
        } catch (IllegalArgumentException ex) {
            return fail(t, "QUALITY_GATE parameter expression invalid: " + ex.getMessage());
        }
        return ok(t);
    }

    /** 校验集成引用节点至少声明其等待的 ODS 资产。 */
    private TaskCompileResult validateSyncRef(PipelineTask t) {
        if (!StringUtils.hasText(t.getTargetFqn())) {
            return fail(t, "SYNC_REF task requires targetFqn (the ODS table FQN it depends on)");
        }
        return ok(t);
    }

    /** 校验 Spark 节点脚本和目标资产这两个最小真实执行条件。 */
    private TaskCompileResult validateSparkTask(PipelineTask t) {
        JsonNode cfg = parseSafeJson(t.getConfig());
        String expectedField = t.getTaskType() == TaskType.PYSPARK ? "script" : "sql";
        String script = textOrEmpty(cfg, expectedField);
        if (!StringUtils.hasText(script)) {
            return fail(t, t.getTaskType().name() + " task requires non-empty config." + expectedField);
        }
        try {
            validateParamExpressions(cfg);
        } catch (IllegalArgumentException ex) {
            return fail(t, t.getTaskType().name() + " parameter expression invalid: " + ex.getMessage());
        }
        if (!StringUtils.hasText(t.getTargetFqn())) {
            return fail(t, t.getTaskType().name() + " task requires targetFqn for catalog, lineage, and quality checks");
        }
        return ok(t);
    }

    private static void validateParamExpressions(JsonNode node) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isTextual()) {
            ParamRenderer.validate(node.asText());
            return;
        }
        if (node.isContainerNode()) {
            node.forEach(PipelineCompileService::validateParamExpressions);
        }
    }

    /** 上游输出引用必须与 PIPELINE 图的传递依赖一致，不能借执行时序读取兄弟节点。 */
    private static List<String> validateUpstreamReferences(List<PipelineTask> tasks,
                                                           List<PipelineTaskEdge> edges) {
        Map<String, Set<String>> parentsByTask = new LinkedHashMap<>();
        for (PipelineTask task : tasks) {
            parentsByTask.put(task.getTaskKey(), new LinkedHashSet<>());
        }
        for (PipelineTaskEdge edge : edges) {
            if (edge.getEdgeLayer() == EdgeLayer.PIPELINE
                    && parentsByTask.containsKey(edge.getSourceKey())
                    && parentsByTask.containsKey(edge.getTargetKey())) {
                parentsByTask.get(edge.getTargetKey()).add(edge.getSourceKey());
            }
        }

        List<String> errors = new ArrayList<>();
        for (PipelineTask task : tasks) {
            Set<String> references;
            try {
                references = upstreamTaskKeys(parseSafeJson(task.getConfig()));
            } catch (IllegalArgumentException ignored) {
                // 节点级参数校验已经给出更精确的语法错误，避免重复图错误。
                continue;
            }
            Set<String> ancestors = collectAncestors(task.getTaskKey(), parentsByTask);
            for (String referencedTask : references) {
                if (!ancestors.contains(referencedTask)) {
                    errors.add("Task " + task.getTaskKey()
                            + " references non-upstream task: " + referencedTask);
                }
            }
        }
        return errors;
    }

    private static Set<String> upstreamTaskKeys(JsonNode node) {
        Set<String> taskKeys = new LinkedHashSet<>();
        if (node == null || node.isNull()) {
            return taskKeys;
        }
        if (node.isTextual()) {
            taskKeys.addAll(ParamRenderer.upstreamTaskKeys(node.asText()));
        } else if (node.isContainerNode()) {
            node.forEach(child -> taskKeys.addAll(upstreamTaskKeys(child)));
        }
        return taskKeys;
    }

    private static Set<String> collectAncestors(String taskKey,
                                                Map<String, Set<String>> parentsByTask) {
        Set<String> ancestors = new LinkedHashSet<>();
        Deque<String> pending = new ArrayDeque<>(
                parentsByTask.getOrDefault(taskKey, Set.of()));
        while (!pending.isEmpty()) {
            String parent = pending.removeFirst();
            if (ancestors.add(parent)) {
                pending.addAll(parentsByTask.getOrDefault(parent, Set.of()));
            }
        }
        return ancestors;
    }

    private static com.fasterxml.jackson.databind.JsonNode parseSafeJson(String json) {
        if (!StringUtils.hasText(json) || "{}".equals(json.trim())) {
            return JsonUtil.mapper().nullNode();
        }
        try {
            return JsonUtil.mapper().readTree(json);
        } catch (Exception e) {
            return JsonUtil.mapper().nullNode();
        }
    }

    private static String textOrEmpty(com.fasterxml.jackson.databind.JsonNode node, String field) {
        if (node == null) return "";
        com.fasterxml.jackson.databind.JsonNode v = node.path(field);
        return v.isTextual() ? v.asText("") : "";
    }

    private TaskCompileResult ok(PipelineTask t) {
        return new TaskCompileResult(
                t.getId(), t.getTaskKey(), t.getTaskType().name(),
                true, t.getTargetFqn(), null);
    }

    private TaskCompileResult fail(PipelineTask t, String message) {
        return new TaskCompileResult(
                t.getId(), t.getTaskKey(), t.getTaskType().name(),
                false, t.getTargetFqn(), message);
    }

    private void applyCompileResult(PipelineTask t, TaskCompileResult r) {
        if (!r.valid()) {
            t.setCompileStatus(TaskCompileStatus.FAILED);
            t.setCompileError(truncate(r.errorMessage(), 4000));
            t.setExecutable(false);
            return;
        }
        boolean executable = t.getTaskType().category() == TaskCategory.EXEC;
        t.setCompileStatus(TaskCompileStatus.VALIDATED);
        t.setCompileError(null);
        t.setExecutable(executable);
    }

    /**
     * 校验悬空边、端口存在性、入边基数、资产解析和节点类型之间的图契约。
     */
    private List<String> validateGraph(List<PipelineTask> tasks,
                                       List<PipelineTaskEdge> edges,
                                       Map<String, PipelineTask> taskByKey) {
        List<String> errors = new ArrayList<>();
        Set<String> knownKeys = taskByKey.keySet();
        Map<String, PipelineNodePortRegistry.NodeContract> contractByKey = new LinkedHashMap<>();
        for (PipelineTask task : tasks) {
            contractByKey.put(task.getTaskKey(), PipelineNodePortRegistry.contractFor(task));
        }
        Map<String, Map<String, Integer>> inputCounts = new LinkedHashMap<>();
        for (PipelineTask task : tasks) {
            inputCounts.put(task.getTaskKey(), new LinkedHashMap<>());
        }
        for (PipelineTaskEdge e : edges) {
            if (!knownKeys.contains(e.getSourceKey())) {
                errors.add("Edge " + e.getId() + " references missing source task_key: " + e.getSourceKey());
            }
            if (!knownKeys.contains(e.getTargetKey())) {
                errors.add("Edge " + e.getId() + " references missing target task_key: " + e.getTargetKey());
            }
            PipelineTask source = taskByKey.get(e.getSourceKey());
            PipelineTask target = taskByKey.get(e.getTargetKey());
            if (source == null || target == null) {
                continue;
            }
            PipelineNodePortRegistry.NodeContract sourceContract = contractByKey.get(source.getTaskKey());
            PipelineNodePortRegistry.NodeContract targetContract = contractByKey.get(target.getTaskKey());
            String sourceOutput = firstText(e.getSourceOutput(), e.getSourcePort(), "out");
            String targetInput = firstText(e.getTargetInput(), e.getTargetPort(), "in");
            if (!sourceContract.hasOutputPort(sourceOutput)) {
                errors.add(String.format(
                        "Edge %s source port '%s' is not declared on task '%s' (%s)",
                        edgeId(e), sourceOutput, source.getTaskKey(), sourceContract.taskType()));
            }
            if (targetContract.inputPorts().isEmpty()) {
                errors.add(String.format(
                        "Edge %s target task '%s' (%s) does not accept input port '%s'",
                        edgeId(e), target.getTaskKey(), targetContract.taskType(), targetInput));
            } else if (!targetContract.hasInputPort(targetInput)) {
                errors.add(String.format(
                        "Edge %s target port '%s' is not declared on task '%s' (%s). Declared inputs: %s",
                        edgeId(e), targetInput, target.getTaskKey(), targetContract.taskType(),
                        targetContract.inputPorts().keySet()));
            }
            String assetFqn = firstText(e.getAssetFqn(), source.getTargetFqn());
            if (!StringUtils.hasText(assetFqn)) {
                errors.add(String.format(
                        "Edge %s cannot resolve assetFqn from edge.assetFqn or source task '%s'.targetFqn",
                        edgeId(e), source.getTaskKey()));
            }
            inputCounts
                    .computeIfAbsent(target.getTaskKey(), ignored -> new LinkedHashMap<>())
                    .merge(targetInput, 1, Integer::sum);
        }
        for (PipelineTask task : tasks) {
            PipelineNodePortRegistry.NodeContract contract = contractByKey.get(task.getTaskKey());
            Map<String, Integer> counts = inputCounts.getOrDefault(task.getTaskKey(), Map.of());
            for (PipelineNodePortRegistry.InputPort inputPort : contract.requiredInputs()) {
                int count = counts.getOrDefault(inputPort.name(), 0);
                if (count < inputPort.minCount()) {
                    errors.add(String.format(
                            "Task '%s' (%s) missing required input port '%s' (expected at least %d, got %d)",
                            task.getTaskKey(), contract.taskType(), inputPort.name(),
                            inputPort.minCount(), count));
                }
            }
            for (Map.Entry<String, Integer> countEntry : counts.entrySet()) {
                PipelineNodePortRegistry.InputPort inputPort = contract.inputPorts().get(countEntry.getKey());
                if (inputPort == null) {
                    continue;
                }
                int count = countEntry.getValue();
                if (count > inputPort.maxCount()) {
                    errors.add(String.format(
                            "Task '%s' (%s) input port '%s' allows at most %d edge(s), got %d",
                            task.getTaskKey(), contract.taskType(), inputPort.name(),
                            inputPort.maxCount(), count));
                }
            }
        }
        return errors;
    }

    private String edgeId(PipelineTaskEdge e) {
        return e.getId() == null ? "(" + e.getSourceKey() + "→" + e.getTargetKey() + ")" : e.getId().toString();
    }

    private static boolean isSparkTask(PipelineTask t) {
        String tt = t.getTaskType().name();
        return TaskType.SPARK_SQL.name().equals(tt) || TaskType.PYSPARK.name().equals(tt);
    }

    /**
     * 将 PIPELINE 边解析为 Spark 节点的结构化输入并回写 config。
     *
     * <p>边是输入资产、别名、端口和新鲜度策略的单一事实来源；编译器据此生成 SQL，
     * 避免画布边和节点 from_tables 配置发生双写漂移。
     */
    private void applyDataflowInputs(List<PipelineTask> tasks, List<PipelineTaskEdge> edges) {
        Map<String, PipelineTask> taskByKey = new LinkedHashMap<>();
        for (PipelineTask task : tasks) {
            taskByKey.put(task.getTaskKey(), task);
        }
        Map<String, List<DataflowInput>> inputsByTarget = new LinkedHashMap<>();
        for (PipelineTaskEdge edge : edges) {
            PipelineTask source = taskByKey.get(edge.getSourceKey());
            PipelineTask target = taskByKey.get(edge.getTargetKey());
            if (source == null || target == null || !isSparkTask(target)) {
                continue;
            }
            String assetFqn = firstText(edge.getAssetFqn(), source.getTargetFqn());
            if (!StringUtils.hasText(assetFqn)) {
                continue;
            }
            String targetInput = firstText(edge.getTargetInput(), edge.getTargetPort(), "in");
            String alias = firstText(edge.getInputAlias(), aliasFor(targetInput, source.getTaskKey()));
            inputsByTarget.computeIfAbsent(target.getTaskKey(), ignored -> new ArrayList<>())
                    .add(new DataflowInput(
                            source.getTaskKey(),
                            assetFqn.trim(),
                            firstText(edge.getSourceOutput(), edge.getSourcePort(), "out"),
                            targetInput,
                            alias,
                            firstText(edge.getJoinRole(), targetInput),
                            firstText(edge.getTriggerPolicy(), "ALL_SUCCEEDED"),
                            firstText(edge.getFreshnessPolicy(), defaultFreshnessPolicy(targetInput))));
        }

        for (PipelineTask task : tasks) {
            if (!isSparkTask(task)) {
                continue;
            }
            List<DataflowInput> inputs = inputsByTarget.getOrDefault(task.getTaskKey(), List.of());
            if (inputs.isEmpty()) {
                continue;
            }
            ObjectNode cfg = objectConfig(task.getConfig());
            ArrayNode fromTables = cfg.putArray("from_tables");
            ArrayNode inputNodes = cfg.putArray("dataflow_inputs");
            LinkedHashSet<String> seenTables = new LinkedHashSet<>();
            inputs.stream()
                    .sorted((a, b) -> inputOrder(a.targetInput()).compareTo(inputOrder(b.targetInput())))
                    .forEach(input -> {
                        if (seenTables.add(input.assetFqn())) {
                            fromTables.add(input.assetFqn());
                        }
                        ObjectNode item = JsonUtil.mapper().createObjectNode();
                        item.put("sourceTaskKey", input.sourceTaskKey());
                        item.put("assetFqn", input.assetFqn());
                        item.put("sourceOutput", input.sourceOutput());
                        item.put("targetInput", input.targetInput());
                        item.put("alias", input.alias());
                        item.put("joinRole", input.joinRole());
                        item.put("triggerPolicy", input.triggerPolicy());
                        item.put("freshnessPolicy", input.freshnessPolicy());
                        inputNodes.add(item);
                    });

            JsonNode dataflow = cfg.path("dataflow");
            String nodeKind = textOrEmpty(dataflow, "nodeKind");
            if ("JOIN".equalsIgnoreCase(nodeKind) && !StringUtils.hasText(textOrEmpty(cfg, "sql"))) {
                String generatedSql = renderJoinSql(task, cfg, inputs);
                if (StringUtils.hasText(generatedSql)) {
                    cfg.put("sql", generatedSql);
                    cfg.put("compiled_sql_generated", true);
                }
            }
            if ("DERIVE_COLUMN".equalsIgnoreCase(nodeKind) && !StringUtils.hasText(textOrEmpty(cfg, "sql"))) {
                String generatedSql = renderDeriveColumnSql(task, cfg, inputs);
                if (StringUtils.hasText(generatedSql)) {
                    cfg.put("sql", generatedSql);
                    cfg.put("compiled_sql_generated", true);
                }
            }
            if ("SINK".equalsIgnoreCase(nodeKind) && !StringUtils.hasText(textOrEmpty(cfg, "sql"))) {
                String generatedSql = renderSinkSql(task, cfg, inputs);
                if (StringUtils.hasText(generatedSql)) {
                    cfg.put("sql", generatedSql);
                    cfg.put("compiled_sql_generated", true);
                }
            }
            task.setConfig(cfg.toString());
        }
    }

    private ObjectNode objectConfig(String json) {
        JsonNode node = parseSafeJson(json);
        if (node != null && node.isObject()) {
            return (ObjectNode) node.deepCopy();
        }
        return JsonUtil.mapper().createObjectNode();
    }

    /** 从左右输入端口和 join 条件渲染结构化 JOIN SQL。 */
    private String renderJoinSql(PipelineTask task, ObjectNode cfg, List<DataflowInput> inputs) {
        DataflowInput left = findInput(inputs, "left", 0);
        DataflowInput right = findInput(inputs, "right", 1);
        if (left == null || right == null || !StringUtils.hasText(task.getTargetFqn())) {
            return "";
        }
        JsonNode dataflow = cfg.path("dataflow");
        String joinType = normalizeJoinType(textOrEmpty(dataflow, "joinType"));
        String leftAlias = firstText(textOrEmpty(dataflow, "leftAlias"), left.alias(), "l");
        String rightAlias = firstText(textOrEmpty(dataflow, "rightAlias"), right.alias(), "r");
        String on = firstText(textOrEmpty(dataflow, "on"), textOrEmpty(dataflow, "condition"));
        String select = firstText(textOrEmpty(dataflow, "select"), leftAlias + ".*, " + rightAlias + ".*");
        if (!"CROSS".equals(joinType) && !StringUtils.hasText(on)) {
            return "";
        }
        String joinClause = "CROSS".equals(joinType)
                ? "CROSS JOIN " + right.assetFqn() + " " + rightAlias
                : joinType + " JOIN " + right.assetFqn() + " " + rightAlias + " ON " + on;
        return """
                CREATE OR REPLACE TABLE %s AS
                SELECT %s
                FROM %s %s
                %s
                """.formatted(task.getTargetFqn(), select, left.assetFqn(), leftAlias, joinClause).trim();
    }

    /** 渲染派生字段节点 SQL，并保留上游字段。 */
    private String renderDeriveColumnSql(PipelineTask task, ObjectNode cfg, List<DataflowInput> inputs) {
        DataflowInput input = findInput(inputs, "in", 0);
        if (input == null || !StringUtils.hasText(task.getTargetFqn())) {
            return "";
        }
        JsonNode dataflow = cfg.path("dataflow");
        List<DerivedColumn> columns = derivedColumns(dataflow);
        if (columns.isEmpty()) {
            return "";
        }
        String alias = firstText(textOrEmpty(dataflow, "sourceAlias"), input.alias(), "src");
        boolean includeSourceColumns = !dataflow.has("includeSourceColumns") || dataflow.path("includeSourceColumns").asBoolean(true);
        List<String> selectItems = new ArrayList<>();
        if (includeSourceColumns) {
            selectItems.add(alias + ".*");
        }
        for (DerivedColumn column : columns) {
            selectItems.add(column.expression() + " AS " + quoteIdentifier(column.name()));
        }
        return """
                CREATE OR REPLACE TABLE %s AS
                SELECT %s
                FROM %s %s
                """.formatted(task.getTargetFqn(), String.join(", ", selectItems), input.assetFqn(), alias).trim();
    }

    /** 渲染单输入落表节点 SQL。 */
    private String renderSinkSql(PipelineTask task, ObjectNode cfg, List<DataflowInput> inputs) {
        DataflowInput input = findInput(inputs, "in", 0);
        if (input == null || !StringUtils.hasText(task.getTargetFqn())) {
            return "";
        }
        JsonNode dataflow = cfg.path("dataflow");
        String alias = firstText(textOrEmpty(dataflow, "sourceAlias"), input.alias(), "src");
        String select = firstText(textOrEmpty(dataflow, "select"), alias + ".*");
        String mode = firstText(textOrEmpty(dataflow, "mode"), textOrEmpty(dataflow, "sinkMode"), "OVERWRITE");
        if ("APPEND".equalsIgnoreCase(mode)) {
            return """
                    INSERT INTO %s
                    SELECT %s
                    FROM %s %s
                    """.formatted(task.getTargetFqn(), select, input.assetFqn(), alias).trim();
        }
        return """
                CREATE OR REPLACE TABLE %s AS
                SELECT %s
                FROM %s %s
                """.formatted(task.getTargetFqn(), select, input.assetFqn(), alias).trim();
    }

    private List<DerivedColumn> derivedColumns(JsonNode dataflow) {
        JsonNode columns = firstArray(
                dataflow.path("deriveColumns"),
                dataflow.path("derivedColumns"),
                dataflow.path("columns"));
        if (columns == null) {
            return List.of();
        }
        List<DerivedColumn> values = new ArrayList<>();
        for (JsonNode item : columns) {
            String name = firstText(
                    textOrEmpty(item, "name"),
                    textOrEmpty(item, "column"),
                    textOrEmpty(item, "target"));
            String expression = firstText(
                    textOrEmpty(item, "expression"),
                    textOrEmpty(item, "expr"));
            if (StringUtils.hasText(name) && StringUtils.hasText(expression)) {
                values.add(new DerivedColumn(name, expression));
            }
        }
        return values;
    }

    private JsonNode firstArray(JsonNode... nodes) {
        if (nodes == null) {
            return null;
        }
        for (JsonNode node : nodes) {
            if (node != null && node.isArray()) {
                return node;
            }
        }
        return null;
    }

    private String quoteIdentifier(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    private DataflowInput findInput(List<DataflowInput> inputs, String port, int fallbackIndex) {
        for (DataflowInput input : inputs) {
            if (port.equalsIgnoreCase(input.targetInput())) {
                return input;
            }
        }
        return inputs.size() > fallbackIndex ? inputs.get(fallbackIndex) : null;
    }

    private String normalizeJoinType(String value) {
        String upper = StringUtils.hasText(value) ? value.trim().toUpperCase() : "LEFT";
        return switch (upper) {
            case "INNER", "LEFT", "RIGHT", "FULL", "CROSS" -> upper;
            case "LEFT OUTER" -> "LEFT";
            case "RIGHT OUTER" -> "RIGHT";
            case "FULL OUTER" -> "FULL";
            default -> "LEFT";
        };
    }

    private String inputOrder(String value) {
        String normalized = value == null ? "" : value.toLowerCase();
        return switch (normalized) {
            case "left" -> "0-left";
            case "right" -> "1-right";
            case "inputs" -> "2-inputs";
            default -> "3-" + normalized;
        };
    }

    private String aliasFor(String targetInput, String sourceKey) {
        String base = StringUtils.hasText(targetInput) && !"in".equalsIgnoreCase(targetInput)
                ? targetInput
                : sourceKey;
        return base.replaceAll("[^A-Za-z0-9_]", "_");
    }

    private String defaultFreshnessPolicy(String targetInput) {
        if ("left".equalsIgnoreCase(targetInput) || "right".equalsIgnoreCase(targetInput)) {
            return "SAME_FRESHNESS_WINDOW";
        }
        return "LATEST";
    }

    private static String firstText(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    /** 从一条入边解析出的结构化数据流输入。 */
    private record DataflowInput(
            String sourceTaskKey,
            String assetFqn,
            String sourceOutput,
            String targetInput,
            String alias,
            String joinRole,
            String triggerPolicy,
            String freshnessPolicy
    ) {}

    /** 派生字段名和受控表达式。 */
    private record DerivedColumn(String name, String expression) {}

    /**
     * 按 PIPELINE 层边做拓扑排序；数据依赖由显式的 {@code pipeline_task_edge} 契约表达。
     */
    /**
     * 对 PIPELINE 边执行稳定 Kahn 拓扑排序；同层节点保持原创建顺序。
     */
    private List<PipelineTask> topologicalSort(List<PipelineTask> tasks,
                                               List<PipelineTaskEdge> edges) {
        Map<String, PipelineTask> byKey = new LinkedHashMap<>();
        for (PipelineTask t : tasks) {
            byKey.put(t.getTaskKey(), t);
        }
        Map<String, Set<String>> outgoing = new HashMap<>();
        Map<String, Integer> indegree = new HashMap<>();
        for (String key : byKey.keySet()) {
            outgoing.put(key, new LinkedHashSet<>());
            indegree.put(key, 0);
        }
        for (PipelineTaskEdge e : edges) {
            if (!EdgeLayer.PIPELINE.name().equals(e.getEdgeLayer().name())) {
                continue;
            }
            if (!byKey.containsKey(e.getSourceKey()) || !byKey.containsKey(e.getTargetKey())) {
                continue;
            }
            if (outgoing.get(e.getSourceKey()).add(e.getTargetKey())) {
                indegree.merge(e.getTargetKey(), 1, Integer::sum);
            }
        }
        List<PipelineTask> ordered = new ArrayList<>(tasks.size());
        List<String> ready = new ArrayList<>();
        for (Map.Entry<String, Integer> e : indegree.entrySet()) {
            if (e.getValue() == 0) {
                ready.add(e.getKey());
            }
        }
        // 入度并列时保留原始节点顺序，确保编译结果稳定。
        ready.sort((a, b) -> Integer.compare(indexOf(tasks, a), indexOf(tasks, b)));
        Set<String> visited = new HashSet<>();
        while (!ready.isEmpty()) {
            String key = ready.remove(0);
            if (!visited.add(key)) continue;
            PipelineTask t = byKey.get(key);
            if (t != null) ordered.add(t);
            List<String> newlyReady = new ArrayList<>();
            for (String next : outgoing.get(key)) {
                int newDeg = indegree.merge(next, -1, Integer::sum);
                if (newDeg == 0) newlyReady.add(next);
            }
            newlyReady.sort((a, b) -> Integer.compare(indexOf(tasks, a), indexOf(tasks, b)));
            ready.addAll(newlyReady);
        }
        if (ordered.size() != tasks.size()) {
            throw new IllegalStateException("cycle among "
                    + (tasks.size() - ordered.size()) + " task(s)");
        }
        return ordered;
    }

    private int indexOf(List<PipelineTask> tasks, String key) {
        for (int i = 0; i < tasks.size(); i++) {
            if (tasks.get(i).getTaskKey().equals(key)) return i;
        }
        return Integer.MAX_VALUE;
    }

    private String truncate(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max);
    }
}
