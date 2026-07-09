package com.onelake.orchestration.dto;

import java.util.List;
import java.util.UUID;

/**
 * 单条流水线的编译结果，覆盖该流水线下所有节点。
 *
 * <p>统一流水线主路径以 Spark 为准。编译阶段产出 Spark 任务配置与图校验结果，
 * 不再把外部模型选择器作为运行契约的一部分。
 *
 * @param pipelineId dag.id
 * @param pipelineTag 流水线标签，例如 {@code pipeline_<uuid>}；用于 Dagster run tag
 * @param tenantId 租户 ID
 * @param tasks 节点级编译结果，按拓扑顺序排列
 * @param allValidated 所有节点是否均编译成功
 * @param graphErrors 图级错误，例如环路或悬空引用
 */
public record PipelineCompileResult(
        UUID pipelineId,
        String pipelineTag,
        UUID tenantId,
        List<TaskCompileResult> tasks,
        boolean allValidated,
        List<String> graphErrors
) {

    public PipelineCompileResult {
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
        graphErrors = graphErrors == null ? List.of() : List.copyOf(graphErrors);
    }

    /**
     * 单个流水线节点的编译结果。
     */
    public record TaskCompileResult(
            UUID taskId,
            String taskKey,
            String taskType,
            boolean valid,
            String targetFqn,
            String errorMessage
    ) {}
}
