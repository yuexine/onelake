package com.onelake.orchestration.domain.enums;

/**
 * 流水线生命周期状态：草稿 → 已校验 → 已发布。
 */
public enum PipelineStatus {
    /** 可编辑草稿，不能进入生产调度。 */
    DRAFT,
    /** 当前图和配置已经校验通过。 */
    VALIDATED,
    /** 已发布，可被调度器和运行接口触发。 */
    PUBLISHED
}
