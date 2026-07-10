package com.onelake.orchestration.dto;

import java.util.List;
import java.util.UUID;

/**
 * ODS→DWD 模板流水线的请求体。
 *
 * <p>在空白流水线中预置 Spark 标准结构：
 * <ul>
 *   <li>{@code SYNC_REF} 指向 {@code sourceFqn}。</li>
 *   <li>按需创建 {@code SPARK_SQL} 字段治理节点。</li>
 *   <li>{@code SPARK_SQL} DWD 目标写入节点写入 {@code targetFqn}。</li>
 *   <li>按需在 Spark 产出的 DWD 表上创建 {@code QUALITY_GATE}。</li>
 * </ul>
 *
 * <p>{@code modelId}/{@code dbtModelName} 保留为历史迁移和命名线索；
 * 统一流水线主路径不再创建外部模型任务。
 *
 * @param pipelineName 新流水线名称
 * @param modelId 已校验的历史 modeling.data_model
 * @param sourceFqn ODS 来源表，也是 SYNC_REF 目标
 * @param targetFqn Spark DWD 输出表
 * @param dbtModelName 历史命名线索，不作为引擎选择器
 * @param includeQualityGate 是否追加质量门禁节点
 * @param includeFieldGovernance 是否追加字段治理节点
 */
public record OdsDwdTemplateRequest(
        String pipelineName,
        UUID modelId,
        String sourceFqn,
        String targetFqn,
        String dbtModelName,
        boolean includeQualityGate,
        boolean includeFieldGovernance
) {}
