package com.onelake.orchestration.service;

import com.onelake.orchestration.domain.entity.PipelineParam;
import com.onelake.orchestration.repository.PipelineParamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 按 GLOBAL、PIPELINE、TASK 顺序解析单个流水线节点的用户参数。 */
@Service
@RequiredArgsConstructor
public class ParamResolver {

    private static final String GLOBAL = "GLOBAL";
    private static final String PIPELINE = "PIPELINE";
    private static final String TASK = "TASK";

    private final PipelineParamRepository paramRepository;

    /**
     * 解析节点的最终用户参数；后读取的窄作用域覆盖前面的宽作用域。
     *
     * <p>{@code taskKey} 为空时只合并 GLOBAL 和 PIPELINE，{@code dagId} 为空时只返回
     * GLOBAL。显式的空参数值归一化为空字符串，未配置任何参数时返回空只读 Map。</p>
     */
    public Map<String, String> resolve(UUID tenantId, UUID dagId, String taskKey) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");

        Map<String, String> resolved = new LinkedHashMap<>();
        merge(resolved, paramRepository.findByTenantIdAndScope(tenantId, GLOBAL));
        if (dagId != null) {
            merge(resolved, paramRepository.findByTenantIdAndDagIdAndScope(
                    tenantId, dagId, PIPELINE));
            if (StringUtils.hasText(taskKey)) {
                merge(resolved, paramRepository.findByTenantIdAndDagIdAndTaskKeyAndScope(
                        tenantId, dagId, taskKey, TASK));
            }
        }
        return resolved.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(resolved));
    }

    /**
     * 用单次数据库查询为一组节点建立不可变参数快照。
     *
     * <p>先构造 GLOBAL→PIPELINE 的公共基线，再分别叠加 TASK 参数。调用方应在一次
     * runConfig 构建开始时调用本方法，确保所有节点共享同一个数据库语句快照。</p>
     */
    public Map<String, Map<String, String>> resolveForTasks(
            UUID tenantId,
            UUID dagId,
            Collection<String> taskKeys) {
        return resolveForTasksInternal(
                tenantId, dagId, taskKeys, null, Map.of(), false);
    }

    /**
     * 单快照解析三级参数，并对显式标记为 EXPR 的值求值一次。
     *
     * <p>普通 STRING/NUMBER/BOOL 参数仍保持字面量，不启用递归替换；EXPR 可引用本节点
     * 合并后的普通参数、运行时内置时间参数和上游输出占位符。上游占位符在首次构建时
     * 继续保留，由节点开始前的最终渲染解析。</p>
     */
    public Map<String, Map<String, String>> resolveForTasks(
            UUID tenantId,
            UUID dagId,
            Collection<String> taskKeys,
            RunContext runContext,
            Map<String, String> builtInParameters) {
        return resolveForTasksInternal(
                tenantId, dagId, taskKeys, runContext, builtInParameters, true);
    }

    private Map<String, Map<String, String>> resolveForTasksInternal(
            UUID tenantId,
            UUID dagId,
            Collection<String> taskKeys,
            RunContext runContext,
            Map<String, String> builtInParameters,
            boolean evaluateExpressions) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(dagId, "dagId must not be null");

        LinkedHashSet<String> requestedKeys = new LinkedHashSet<>();
        if (taskKeys != null) {
            taskKeys.stream().filter(StringUtils::hasText).forEach(requestedKeys::add);
        }
        if (requestedKeys.isEmpty()) {
            return Map.of();
        }

        List<PipelineParam> allParams = paramRepository.findForResolution(
                tenantId, dagId, requestedKeys);
        List<PipelineParam> safeParams = allParams == null ? List.of() : allParams;

        Map<String, ResolvedParam> base = new LinkedHashMap<>();
        mergeResolved(base, byScope(safeParams, GLOBAL));
        mergeResolved(base, byScope(safeParams, PIPELINE));

        Map<String, Map<String, String>> resolvedByTask = new LinkedHashMap<>();
        for (String taskKey : requestedKeys) {
            Map<String, ResolvedParam> resolved = new LinkedHashMap<>(base);
            mergeResolved(resolved, safeParams.stream()
                    .filter(Objects::nonNull)
                    .filter(param -> TASK.equals(param.getScope()))
                    .filter(param -> taskKey.equals(param.getTaskKey()))
                    .toList());
            resolvedByTask.put(taskKey, toValues(
                    resolved, runContext, builtInParameters, evaluateExpressions));
        }
        return Collections.unmodifiableMap(resolvedByTask);
    }

    private void merge(Map<String, String> target, List<PipelineParam> params) {
        if (params == null) {
            return;
        }
        for (PipelineParam param : params) {
            if (param != null && StringUtils.hasText(param.getParamKey())) {
                target.put(param.getParamKey(), param.getParamValue() == null ? "" : param.getParamValue());
            }
        }
    }

    private void mergeResolved(Map<String, ResolvedParam> target, List<PipelineParam> params) {
        if (params == null) {
            return;
        }
        for (PipelineParam param : params) {
            if (param != null && StringUtils.hasText(param.getParamKey())) {
                target.put(param.getParamKey(), new ResolvedParam(
                        param.getParamValue() == null ? "" : param.getParamValue(),
                        StringUtils.hasText(param.getValueType())
                                ? param.getValueType().trim().toUpperCase(java.util.Locale.ROOT)
                                : "STRING"));
            }
        }
    }

    private Map<String, String> toValues(Map<String, ResolvedParam> resolved,
                                         RunContext runContext,
                                         Map<String, String> builtInParameters,
                                         boolean evaluateExpressions) {
        if (resolved.isEmpty()) {
            return Map.of();
        }
        Map<String, String> rawValues = new LinkedHashMap<>();
        resolved.forEach((key, value) -> rawValues.put(key, value.value()));
        Map<String, String> expressionParams = RunContext.mergeWithBuiltIns(
                rawValues, builtInParameters == null ? Map.of() : builtInParameters);
        Map<String, String> values = new LinkedHashMap<>();
        resolved.forEach((key, value) -> values.put(
                key,
                evaluateExpressions && "EXPR".equals(value.valueType())
                        ? ParamRenderer.render(value.value(), runContext, expressionParams)
                        : value.value()));
        return Collections.unmodifiableMap(values);
    }

    private List<PipelineParam> byScope(List<PipelineParam> params, String scope) {
        return params.stream()
                .filter(param -> param != null && scope.equals(param.getScope()))
                .toList();
    }

    private record ResolvedParam(String value, String valueType) {
    }
}
