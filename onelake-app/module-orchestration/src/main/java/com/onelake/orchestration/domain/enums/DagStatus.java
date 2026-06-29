package com.onelake.orchestration.domain.enums;

/**
 * Unified RunStatus (C8 — see docs/RUNSTATUS_ENUM_AUDIT.md).
 *
 * <p>Canonical terminal success is
 * {@link #SUCCEEDED}, matching {@code TaskRunStatus} and Outbox payload schema v2.
 *
 * <p>Backward compat for status strings coming from external sources (e.g. Dagster
 * raw responses) is handled in {@code OrchestrationService.mapDagsterStatus}.
 */
public enum DagStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED
}
