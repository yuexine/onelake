package com.onelake.orchestration.service.spi;

import java.util.Map;
import java.util.UUID;

/**
 * 单个流水线节点的引擎无关描述，由 {@code orchestration.pipeline_task}
 * 和解析后的业务配置组装而来。
 *
 * <p>统一流水线主路径当前只生成 Spark 家族描述：
 * {@code SPARK_SQL}、{@code PYSPARK}、{@code QUALITY_GATE} 和 {@code SYNC_REF}。
 */
public record TaskDescriptor(
        UUID taskId,
        String taskKey,
        String taskType,            // 节点类型：QUALITY_GATE|SYNC_REF|SPARK_SQL|PYSPARK。
        EngineType engine,
        String targetFqn,
        UUID modelId,               // 历史兼容字段；新 Spark-only 节点为空。
        UUID syncTaskId,            // 可空，仅 SYNC_REF 使用。
        Map<String, Object> businessConfig
) {
    public TaskDescriptor {
        businessConfig = businessConfig == null ? Map.of() : Map.copyOf(businessConfig);
    }
}
