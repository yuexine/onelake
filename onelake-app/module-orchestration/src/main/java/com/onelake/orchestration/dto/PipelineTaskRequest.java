package com.onelake.orchestration.dto;

import java.util.Map;
import java.util.UUID;

/**
 * 创建或更新单个 {@code pipeline_task} 的请求体。
 *
 * <p>统一流水线编辑器当前只创建 Spark 家族节点。
 *
 * @param taskKey 流水线内稳定节点键
 * @param taskType QUALITY_GATE、SYNC_REF、SPARK_SQL 或 PYSPARK
 * @param name 展示名称
 * @param engine 可选执行引擎；默认 SPARK_SQL
 * @param targetFqn 输出表全限定名
 * @param modelId 历史兼容字段，Spark-only 流水线忽略
 * @param syncTaskId SYNC_REF 指向的同步任务
 * @param config 节点业务与运行配置
 * @param positionX 画布横坐标
 * @param positionY 画布纵坐标
 */
public record PipelineTaskRequest(
        String taskKey,
        String taskType,
        String name,
        String engine,
        String targetFqn,
        UUID modelId,
        UUID syncTaskId,
        Map<String, Object> config,
        Integer positionX,
        Integer positionY
) {}
