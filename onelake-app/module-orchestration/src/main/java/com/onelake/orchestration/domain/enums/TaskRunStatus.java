package com.onelake.orchestration.domain.enums;

/**
 * Status of a single task_run row.
 *
 * <p>Aligned with the unified RunStatus:
 * QUEUED|RUNNING|SUCCEEDED|FAILED|CANCELLED|UPSTREAM_FAILED|SKIPPED.
 * See C8 / docs/流水线模块重设计方案.md §6.1, §7 P0 audit doc.
 */
public enum TaskRunStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    UPSTREAM_FAILED,
    SKIPPED
}
