package com.onelake.orchestration.dto;

import java.util.List;
import java.util.UUID;

/**
 * Result of applying an ODS→DWD template (P3).
 */
public record OdsDwdTemplateResult(
        UUID pipelineId,
        List<UUID> taskIds,
        List<UUID> edgeIds,
        String warnings
) {}
