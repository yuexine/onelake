package com.onelake.orchestration.domain.enums;

/**
 * 单个业务日期回填子运行状态。
 */
public enum BackfillRunStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED
}
