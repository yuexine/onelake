package com.onelake.orchestration.dto;

import java.util.List;
import java.util.UUID;

/**
 * ODS→DWD 模板应用结果。
 */
public record OdsDwdTemplateResult(
        UUID pipelineId,
        List<UUID> taskIds,
        List<UUID> edgeIds,
        String warnings
) {}
