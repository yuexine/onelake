package com.onelake.orchestration.service.spi;

import com.onelake.orchestration.dto.PipelineCompileResult;

import java.util.List;
import java.util.UUID;

/**
 * 传给 {@link EngineRunConfigBuilder} 的上下文，包含引擎构建 Dagster runConfig 所需的全部信息。
 *
 * <p><b>单一事实来源</b>：{@link #compileResult} 由 {@code PipelineCompileService}
 * 基于 Spark 流水线节点和边契约生成，不再从历史模型任务配置派生。
 *
 * @param pipelineId DAG/流水线 ID
 * @param tenantId 当前运行所属租户
 * @param runId 本地 JobRun ID
 * @param compileResult 已通过校验并按拓扑排序的编译结果
 * @param pipelineTag Dagster 运行标签，格式为 {@code pipeline_<id>}
 * @param resourceGroup DAG 选择的资源组编码
 * @param computeProfile DAG 选择的计算规格编码
 */
public record TaskBundleContext(
        UUID pipelineId,
        UUID tenantId,
        UUID runId,
        PipelineCompileResult compileResult,
        String pipelineTag,
        String resourceGroup,
        String computeProfile
) {
}
