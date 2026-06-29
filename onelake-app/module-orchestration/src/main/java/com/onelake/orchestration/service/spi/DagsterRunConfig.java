package com.onelake.orchestration.service.spi;

import java.util.Map;

/**
 * Immutable description of a single engine's contribution to a Dagster run.
 *
 * <p>The Java control plane builds this; Dagster ops consume it verbatim. Java never executes
 * runtime engines directly (see C3 in 流水线模块重设计方案.md §6.3).
 *
 * @param jobName     Dagster job name, e.g. {@code onelake_pipeline_run}
 * @param opConfig    Full Dagster runConfigData sent to GraphQL launchRun. Example:
 *                    {@code ops -> run_spark_task_op -> config -> { tasks, resource_profile }}
 */
public record DagsterRunConfig(String jobName, Map<String, Object> opConfig) {

    public DagsterRunConfig {
        opConfig = opConfig == null ? Map.of() : Map.copyOf(opConfig);
    }
}
