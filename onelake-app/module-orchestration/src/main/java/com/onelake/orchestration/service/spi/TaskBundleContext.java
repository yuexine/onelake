package com.onelake.orchestration.service.spi;

import com.onelake.orchestration.dto.PipelineCompileResult;

import java.util.List;
import java.util.UUID;

/**
 * 传给 {@link EngineRunConfigBuilder} 的上下文，包含引擎构建 Dagster runConfig 所需的全部信息。
 *
 * <p><b>单一事实来源</b>：{@link #compileResult} 由 {@code PipelineCompileService}
 * 基于 Spark 流水线节点和边契约生成，不再从历史模型任务配置派生。
 */
public record TaskBundleContext(
        UUID pipelineId,
        UUID tenantId,
        UUID runId,
        PipelineCompileResult compileResult,
        String pipelineTag,          // 形如 "pipeline_<id>"，用作 Dagster run tag。
        String resourceGroup,        // dag.resource_group。
        String computeProfile        // dag.compute_profile。
) {
}
