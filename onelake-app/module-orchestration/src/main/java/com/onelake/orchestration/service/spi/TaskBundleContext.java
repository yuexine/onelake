package com.onelake.orchestration.service.spi;

import com.onelake.orchestration.dto.PipelineCompileResult;

import java.util.List;
import java.util.UUID;

/**
 * Input passed to {@link EngineRunConfigBuilder}. Carries everything an engine needs to build
 * a Dagster runConfig for its slice of the pipeline.
 *
 * <p><b>C1 single source of truth (§6.1)</b>: the {@link #compileResult} was built by
 * {@code PipelineCompileService} from Spark-only pipeline tasks and edge contracts — never from
 * historical model-backed task config.
 */
public record TaskBundleContext(
        UUID pipelineId,
        UUID tenantId,
        UUID runId,
        PipelineCompileResult compileResult,
        String pipelineTag,          // "pipeline_<id>" — used as a Dagster run tag
        String resourceGroup,        // dag.resource_group
        String computeProfile        // dag.compute_profile
) {
}
