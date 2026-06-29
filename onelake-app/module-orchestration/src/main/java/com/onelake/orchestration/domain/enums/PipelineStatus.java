package com.onelake.orchestration.domain.enums;

/**
 * Pipeline lifecycle status (draft→validated→published workflow).
 * See docs/流水线模块重设计方案.md §6.1, §7 P4.
 */
public enum PipelineStatus {
    DRAFT,
    VALIDATED,
    PUBLISHED
}
