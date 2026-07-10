package com.onelake.orchestration.dto;

/**
 * 创建流水线边的请求体。
 *
 * <p>sourceKey/targetKey 标识执行依赖，端口与 output/input 标识数据契约；其余字段
 * 用于多输入角色、资产血缘和触发策略。
 *
 * @param sourceKey 上游 taskKey
 * @param targetKey 下游 taskKey
 * @param edgeLayer PIPELINE 或 CROSS_ENGINE；为空时默认 PIPELINE
 * @param sourcePort 上游输出端口
 * @param targetPort 下游输入端口
 * @param sourceOutput 上游输出名称
 * @param targetInput 下游输入名称
 * @param assetFqn 边承载的数据资产
 * @param inputAlias 下游输入别名
 * @param joinRole 多输入连接角色
 * @param triggerPolicy 触发策略
 * @param freshnessPolicy 新鲜度策略
 * @param auto 是否自动生成
 */
public record PipelineTaskEdgeRequest(
        String sourceKey,
        String targetKey,
        String edgeLayer,
        String sourcePort,
        String targetPort,
        String sourceOutput,
        String targetInput,
        String assetFqn,
        String inputAlias,
        String joinRole,
        String triggerPolicy,
        String freshnessPolicy,
        Boolean auto
) {}
