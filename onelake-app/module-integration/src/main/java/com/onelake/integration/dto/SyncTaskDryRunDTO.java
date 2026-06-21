package com.onelake.integration.dto;

import java.util.List;

public record SyncTaskDryRunDTO(
    boolean ready,
    List<Check> checks
) {
    public record Check(
        String code,
        String label,
        boolean passed,
        String message
    ) {}
}
