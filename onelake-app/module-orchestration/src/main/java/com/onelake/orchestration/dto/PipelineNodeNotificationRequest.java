package com.onelake.orchestration.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** Internal request emitted by a Dagster NOTIFY node after parameter rendering. */
public record PipelineNodeNotificationRequest(
        @NotNull UUID parentRunId,
        @NotBlank @Size(max = 128) String taskKey,
        UUID receiverId,
        @NotBlank @Size(max = 256) String title,
        @NotNull String message,
        @Size(max = 512) String link,
        @Size(max = 16) String level) {
}
