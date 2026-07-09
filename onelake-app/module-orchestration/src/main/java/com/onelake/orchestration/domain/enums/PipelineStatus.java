package com.onelake.orchestration.domain.enums;

/**
 * 流水线生命周期状态：草稿 → 已校验 → 已发布。
 */
public enum PipelineStatus {
    DRAFT,
    VALIDATED,
    PUBLISHED
}
