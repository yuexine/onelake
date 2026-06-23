package com.onelake.orchestration.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ResourceGroupDTO(
    UUID id,
    String code,
    String displayName,
    String engine,
    String status,
    boolean builtin,
    Integer maxConcurrency,
    Integer quotaCpu,
    Integer quotaMemoryGb,
    Map<String, Object> costPolicy,
    List<ComputeProfileDTO> computeProfiles,
    Instant createdAt,
    Instant updatedAt
) {
}
