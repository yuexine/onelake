package com.onelake.orchestration.dto;

import java.time.Instant;
import java.util.UUID;

/** 流水线不可变发布版本摘要。 */
public record PipelineVersionSummaryDTO(
        UUID id,
        UUID dagId,
        Integer version,
        String checksum,
        String status,
        String note,
        UUID publishedBy,
        String publishedByName,
        Instant createdAt
) {}
