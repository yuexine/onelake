package com.onelake.orchestration.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/** 流水线不可变发布版本详情，包含可直接展示的完整快照。 */
public record PipelineVersionDetailDTO(
        UUID id,
        UUID dagId,
        Integer version,
        String checksum,
        String status,
        String note,
        UUID publishedBy,
        String publishedByName,
        Instant createdAt,
        JsonNode snapshot
) {}
