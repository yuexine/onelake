package com.onelake.orchestration.domain.enums;

/**
 * 单条 {@code task_run} 的节点运行状态。
 *
 * <p>与统一运行状态口径保持一致：
 * {@code QUEUED|RUNNING|SUCCEEDED|FAILED|CANCELLED|UPSTREAM_FAILED|SKIPPED}。
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
