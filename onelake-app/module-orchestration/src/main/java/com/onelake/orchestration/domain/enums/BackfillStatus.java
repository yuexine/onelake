package com.onelake.orchestration.domain.enums;

/**
 * 回填批次聚合状态。
 */
public enum BackfillStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    PARTIAL
}
