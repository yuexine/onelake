package com.onelake.orchestration.dto;

import java.util.List;
import java.util.UUID;

/**
 * Result of compiling one pipeline (all tasks).
 *
 * <p>The unified pipeline mainline is Spark-only. Compilation produces Spark task configs
 * and graph validation results; external model selectors are no longer part of this contract.
 *
 * @param pipelineId     dag.id
 * @param pipelineTag    e.g. {@code pipeline_<uuid>} — informational; used as a Dagster run tag
 * @param tenantId       tenant
 * @param tasks          per-task compile results, in topological order
 * @param allValidated   true if every task compiled successfully
 * @param graphErrors    graph-level errors (e.g. cycles, dangling refs)
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

    public record TaskCompileResult(
            UUID taskId,
            String taskKey,
            String taskType,
            boolean valid,
            String targetFqn,
            String errorMessage
    ) {}
}
