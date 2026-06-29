package com.onelake.orchestration.service.spi;

/**
 * Builds the Dagster runConfig slice for one execution engine.
 *
 * <p><b>Responsibility (C3, §6.3)</b>: Java builds runConfig only. Dagster executes.
 * Implementations must NOT invoke runtime engines directly, write to {@code modeling.*} schema,
 * or perform any side effect beyond constructing the runConfig record.
 *
 * <p>Implementations are discovered by Spring as {@link org.springframework.stereotype.Component @Component}
 * beans; {@code OrchestrationService} looks them up by {@link #engine()}.
 */
public interface EngineRunConfigBuilder {

    /** Which engine this builder serves. */
    EngineType engine();

    /**
     * Build the Dagster runConfig for this engine's slice of the pipeline.
     *
     * <p>The bundle's {@link TaskBundleContext#tasks()} already contains only tasks whose
     * engine matches {@link #engine()} — no need to re-filter.
     */
    DagsterRunConfig build(TaskBundleContext ctx);
}
