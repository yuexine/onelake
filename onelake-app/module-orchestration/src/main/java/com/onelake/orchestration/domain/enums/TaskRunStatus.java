package com.onelake.orchestration.domain.enums;

/**
 * 单条 {@code task_run} 的节点运行状态。
 *
 * <p>与统一运行状态口径保持一致：
 * {@code QUEUED|RUNNING|SUCCEEDED|FAILED|CANCELLED|UPSTREAM_FAILED|SKIPPED}。
 */
public enum TaskRunStatus {
    /** 等待前置条件或执行器领取。 */
    QUEUED,
    /** 正在执行。 */
    RUNNING,
    /** 节点成功完成。 */
    SUCCEEDED,
    /** 节点自身执行失败。 */
    FAILED,
    /** 用户或系统取消。 */
    CANCELLED,
    /** 上游失败导致本节点无法执行。 */
    UPSTREAM_FAILED,
    /** 根据运行策略无需执行。 */
    SKIPPED
}
