package com.onelake.orchestration.domain.enums;

/**
 * 单个业务周期回填队列明细状态。
 *
 * <p>QUEUED 等待并发槽位；RUNNING 已派发；SUCCEEDED、FAILED、CANCELLED
 * 为终态并参与批次进度聚合。
 */
public enum BackfillRunStatus {
    /** 已持久化，等待批次和 DAG 并发槽位。 */
    QUEUED,
    /** 已领取并开始创建或执行对应 JobRun。 */
    RUNNING,
    /** 对应 JobRun 成功。 */
    SUCCEEDED,
    /** 派发失败或对应 JobRun 失败。 */
    FAILED,
    /** 批次取消或对应 JobRun 被取消。 */
    CANCELLED
}
