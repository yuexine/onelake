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
    /** 已创建但尚未进入执行，占用 max_active_runs 槽位。 */
    QUEUED,
    /** Dagster 或本地任务仍在执行，占用 max_active_runs 槽位。 */
    RUNNING,
    /** 全部工作成功完成。 */
    SUCCEEDED,
    /** 编译、启动或执行失败。 */
    FAILED,
    /** 用户取消或外部运行被终止。 */
    CANCELLED
}
