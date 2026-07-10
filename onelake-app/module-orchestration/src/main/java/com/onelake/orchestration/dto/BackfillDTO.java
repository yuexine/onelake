package com.onelake.orchestration.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 回填批次响应对象。
 */
public record BackfillDTO(
        UUID id,
        @JsonProperty("dag_id")
        UUID dagId,
        String status,
        int total,
        int succeeded,
        int failed,
        @JsonProperty("max_parallel")
        int maxParallel,
        Range range,
        String grain,
        String timezone,
        @JsonProperty("created_at")
        Instant createdAt,
        @JsonProperty("updated_at")
        Instant updatedAt,
        List<BackfillRunDTO> runs
) {
    public BackfillDTO {
        range = range == null ? new Range(null, null) : range;
        runs = runs == null ? List.of() : List.copyOf(runs);
    }

    public Instant rangeStart() {
        return range.start();
    }

    public Instant rangeEnd() {
        return range.end();
    }

    public int totalRuns() {
        return total;
    }

    public int succeededRuns() {
        return succeeded;
    }

    public int failedRuns() {
        return failed;
    }

    public record Range(
            Instant start,
            Instant end
    ) {}
}
