package com.onelake.orchestration.dto;

import java.util.List;
import java.util.UUID;

/**
 * ODS→DWD 模板应用结果。
 *
 * @param pipelineId 目标流水线 ID
 * @param taskIds 模板创建的节点 ID
 * @param edgeIds 模板创建的边 ID
 * @param warnings 非阻断告警摘要
 */
public record OdsDwdTemplateResult(
        UUID pipelineId,
        List<UUID> taskIds,
        List<UUID> edgeIds,
        String warnings
) {}
