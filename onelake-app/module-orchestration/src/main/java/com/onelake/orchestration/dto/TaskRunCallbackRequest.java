package com.onelake.orchestration.dto;

import com.onelake.orchestration.domain.enums.TaskRunStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record TaskRunCallbackRequest(
        @NotNull TaskRunStatus status,
        Instant startedAt,
        Instant finishedAt,
        @Size(max = 4000) String errorMsg,
        @Size(max = 512) String artifactPath,
        @PositiveOrZero Long rowsWritten,
        @PositiveOrZero Long scanBytes,
        @Size(max = 512) String logRef,
        @Min(1) Integer attempt,
        @Size(max = 128) String dagsterStepKey
) {
}
