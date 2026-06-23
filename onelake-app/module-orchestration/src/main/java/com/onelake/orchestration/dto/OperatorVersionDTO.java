package com.onelake.orchestration.dto;

import java.time.Instant;
import java.util.UUID;

public record OperatorVersionDTO(
    UUID id,
    String version,
    OperatorManifestDTO manifest,
    String changelog,
    UUID createdBy,
    Instant createdAt
) {
}
