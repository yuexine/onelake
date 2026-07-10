package com.onelake.orchestration.dto;

import com.onelake.orchestration.domain.entity.PipelineTaskEdge;
import com.onelake.orchestration.domain.enums.EdgeLayer;

import java.time.Instant;
import java.util.UUID;

/**
 * 流水线边接口响应对象。
 *
 * <p>除画布端口外，还携带资产、输入别名、触发和新鲜度策略，供编译器构建真实数据流。
 *
 * @param id 边 ID
 * @param dagId 所属流水线
 * @param sourceKey 上游 taskKey
 * @param targetKey 下游 taskKey
 * @param edgeLayer PIPELINE 执行依赖或 CROSS_ENGINE 逻辑边
 * @param sourcePort 上游输出端口
 * @param targetPort 下游输入端口
 * @param sourceOutput 上游输出名称
 * @param targetInput 下游输入名称
 * @param assetFqn 边承载的数据资产
 * @param inputAlias 下游引用该输入的别名
 * @param joinRole 多输入节点中的连接角色
 * @param triggerPolicy 触发策略
 * @param freshnessPolicy 新鲜度策略
 * @param auto 是否由模板/编译器自动生成
 * @param createdAt 创建时间
 */
public record PipelineTaskEdgeDTO(
        UUID id,
        UUID dagId,
        String sourceKey,
        String targetKey,
        EdgeLayer edgeLayer,
        String sourcePort,
        String targetPort,
        String sourceOutput,
        String targetInput,
        String assetFqn,
        String inputAlias,
        String joinRole,
        String triggerPolicy,
        String freshnessPolicy,
        Boolean auto,
        Instant createdAt
) {
    /** 从持久化实体创建稳定 API 投影。 */
    public static PipelineTaskEdgeDTO of(PipelineTaskEdge e) {
        return new PipelineTaskEdgeDTO(
                e.getId(), e.getDagId(),
                e.getSourceKey(), e.getTargetKey(),
                e.getEdgeLayer(),
                e.getSourcePort(), e.getTargetPort(),
                e.getSourceOutput(), e.getTargetInput(),
                e.getAssetFqn(), e.getInputAlias(), e.getJoinRole(),
                e.getTriggerPolicy(), e.getFreshnessPolicy(),
                e.getAuto(),
                e.getCreatedAt()
        );
    }
}
