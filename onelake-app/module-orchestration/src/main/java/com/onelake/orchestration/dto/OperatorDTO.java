package com.onelake.orchestration.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OperatorDTO(
    UUID id,
    String operatorRef,
    String category,
    String scope,
    String displayName,
    String description,
    String latestVersion,
    String status,
    boolean installed,
    String pinnedVersion,
    OperatorManifestDTO manifest,
    List<OperatorVersionDTO> versions,
    Instant createdAt
) {
}
