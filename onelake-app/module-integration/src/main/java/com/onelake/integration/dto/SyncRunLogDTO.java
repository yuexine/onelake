package com.onelake.integration.dto;

import java.time.Instant;

public record SyncRunLogDTO(
    Instant timestamp,
    String level,
    String message
) {}
