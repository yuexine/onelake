package com.onelake.orchestration.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** Internal request emitted by a Dagster SUB_PIPELINE node. */
public record SubPipelineTriggerRequest(
        @NotNull UUID parentRunId,
        @NotBlank @Size(max = 128) String taskKey,
        @NotNull UUID subDagId,
        @NotNull @Min(1) Integer attempt) {
}
