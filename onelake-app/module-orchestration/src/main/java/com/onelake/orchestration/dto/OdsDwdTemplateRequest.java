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
 */
public record OdsDwdTemplateRequest(
        String pipelineName,
        UUID modelId,           // 已校验的历史 modeling.data_model。
        String sourceFqn,       // model.source_fqn，作为 SYNC_REF 目标。
        String targetFqn,       // Spark DWD 目标写入表。
        String dbtModelName,    // 历史命名线索，不作为引擎选择器。
        boolean includeQualityGate,
        boolean includeFieldGovernance
) {}
