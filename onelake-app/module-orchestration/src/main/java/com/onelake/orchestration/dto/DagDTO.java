package com.onelake.orchestration.dto;

import java.time.Instant;
import java.util.UUID;

public record DagDTO(
    UUID id,
    String name,
    String dagsterJob,
    String scheduleCron,
    Boolean enabled,
    Integer version,
    Instant createdAt
) {}
