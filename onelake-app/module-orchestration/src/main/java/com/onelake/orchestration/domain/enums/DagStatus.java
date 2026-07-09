package com.onelake.orchestration.domain.enums;

/**
 * 运行状态统一口径。
 *
 * <p>标准成功终态为 {@link #SUCCEEDED}，与 {@code TaskRunStatus}
 * 和 Outbox v2 载荷保持一致。
 *
 * <p>来自外部系统的状态字符串（例如 Dagster 原始响应）由
 * {@code OrchestrationService.mapDagsterStatus} 做兼容映射。
 */
public enum DagStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED
}
