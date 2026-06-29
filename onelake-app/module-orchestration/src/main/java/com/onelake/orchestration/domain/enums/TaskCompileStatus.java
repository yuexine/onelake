package com.onelake.orchestration.domain.enums;

/**
 * Compile status for a single pipeline_task.
 * See docs/流水线模块重设计方案.md §6.1.
 */
public enum TaskCompileStatus {
    DRAFT,
    VALIDATED,
    FAILED
}
