package com.onelake.orchestration.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 算子版本接口响应对象。
 */
public record OperatorVersionDTO(
    UUID id,
    String version,
    OperatorManifestDTO manifest,
    String changelog,
    UUID createdBy,
    Instant createdAt
) {
}
