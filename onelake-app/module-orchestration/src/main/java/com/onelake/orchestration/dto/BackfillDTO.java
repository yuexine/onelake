package com.onelake.orchestration.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 回填批次响应对象。
 */
public record BackfillDTO(
        UUID id,
        UUID dagId,
        String status,
        Instant rangeStart,
        Instant rangeEnd,
        String grain,
        int totalRuns,
        int succeededRuns,
        int failedRuns,
        int maxParallel,
        Instant createdAt,
        Instant updatedAt,
        List<BackfillRunDTO> runs
) {
    public BackfillDTO {
        runs = runs == null ? List.of() : List.copyOf(runs);
    }
}
