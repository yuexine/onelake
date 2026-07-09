package com.onelake.orchestration.dto;

import java.util.Map;
import java.util.UUID;

/**
 * 创建或更新单个 {@code pipeline_task} 的请求体。
 *
 * <p>统一流水线编辑器当前只创建 Spark 家族节点。
 */
public record PipelineTaskRequest(
        String taskKey,
        String taskType,            // 节点类型：QUALITY_GATE|SYNC_REF|SPARK_SQL|PYSPARK。
        String name,
        String engine,              // 可选；默认 SPARK_SQL。
        String targetFqn,
        UUID modelId,               // 历史兼容字段；Spark-only 流水线忽略。
        UUID syncTaskId,            // 仅 SYNC_REF 使用。
        Map<String, Object> config,
        Integer positionX,
        Integer positionY
) {}
