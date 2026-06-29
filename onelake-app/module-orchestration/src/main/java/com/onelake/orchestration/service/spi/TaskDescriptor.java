package com.onelake.orchestration.service.spi;

import java.util.Map;
import java.util.UUID;

/**
 * Engine-agnostic description of a single pipeline task, derived from
 * {@code orchestration.pipeline_task} plus its resolved business config.
 *
 * <p>The unified pipeline mainline creates Spark-family descriptors only:
 * {@code SPARK_SQL}, {@code PYSPARK}, {@code QUALITY_GATE}, and {@code SYNC_REF}.
 */
public record TaskDescriptor(
        UUID taskId,
        String taskKey,
        String taskType,            // QUALITY_GATE|SYNC_REF|SPARK_SQL|PYSPARK
        EngineType engine,
        String targetFqn,
        UUID modelId,               // deprecated; null for new Spark-only tasks
        UUID syncTaskId,            // nullable; only for SYNC_REF
        Map<String, Object> businessConfig
) {
    public TaskDescriptor {
        businessConfig = businessConfig == null ? Map.of() : Map.copyOf(businessConfig);
    }
}
