package com.onelake.analytics.domain.enums;

/**
 * Notebook 运行状态（强约束：与 modeling.model_run 对齐，见 docs/RUNSTATUS_ENUM_AUDIT.md）。
 * 禁止再用 SUCCESS / DONE / FINISHED 等历史命名。
 */
public enum RunStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED
}
