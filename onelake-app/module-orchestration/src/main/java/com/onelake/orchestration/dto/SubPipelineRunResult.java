package com.onelake.orchestration.dto;

import com.onelake.orchestration.domain.enums.DagStatus;

import java.util.UUID;

/** Accepted child pipeline run and its current authoritative backend status. */
public record SubPipelineRunResult(UUID runId, DagStatus status) {
}
