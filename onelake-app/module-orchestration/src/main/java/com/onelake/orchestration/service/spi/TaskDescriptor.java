package com.onelake.orchestration.service.spi;

import java.util.Map;
import java.util.UUID;

/**
 * 单个流水线节点的引擎无关描述，由 {@code orchestration.pipeline_task}
 * 和解析后的业务配置组装而来。
 *
 * <p>统一流水线主路径当前只生成 Spark 家族描述：
 * {@code SPARK_SQL}、{@code PYSPARK}、{@code QUALITY_GATE} 和 {@code SYNC_REF}。
 *
 * @param taskId PipelineTask 主键
 * @param taskKey 流水线内稳定节点键，也是 GRAPH 模式的 Dagster step key
 * @param taskType 节点类型
 * @param engine 承载该节点的运行时引擎
 * @param targetFqn 输出表全限定名
 * @param modelId 历史兼容字段；新 Spark-only 节点为空
 * @param syncTaskId SYNC_REF 引用的同步任务；其他节点为空
 * @param businessConfig 去除控制面字段后的业务配置
 */
public record TaskDescriptor(
        UUID taskId,
        String taskKey,
        String taskType,
        EngineType engine,
        String targetFqn,
        UUID modelId,
        UUID syncTaskId,
        Map<String, Object> businessConfig
) {
    public TaskDescriptor {
        businessConfig = businessConfig == null ? Map.of() : Map.copyOf(businessConfig);
    }
}
