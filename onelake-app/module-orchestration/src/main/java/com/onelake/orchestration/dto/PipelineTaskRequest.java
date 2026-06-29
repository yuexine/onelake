package com.onelake.orchestration.dto;

import java.util.Map;
import java.util.UUID;

/**
 * Create/update request body for one pipeline_task.
 *
 * <p>The unified pipeline editor creates Spark-family tasks only.
 */
public record PipelineTaskRequest(
        String taskKey,
        String taskType,            // QUALITY_GATE|SYNC_REF|SPARK_SQL|PYSPARK
        String name,
        String engine,              // optional; defaults to SPARK_SQL
        String targetFqn,
        UUID modelId,               // deprecated; ignored by Spark-only pipelines
        UUID syncTaskId,            // SYNC_REF only
        Map<String, Object> config,
        Integer positionX,
        Integer positionY
) {}
