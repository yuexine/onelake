package com.onelake.orchestration.domain.enums;

/**
 * 回填批次聚合状态。
 *
 * <p>QUEUED/RUNNING 为可继续派发状态；SUCCEEDED/FAILED/PARTIAL/CANCELLED
 * 为终态。PARTIAL 表示同时存在成功和失败/取消子运行。
 */
public enum BackfillStatus {
    /** 批次已创建，子运行尚未开始或均在等待槽位。 */
    QUEUED,
    /** 至少一个子运行已派发或已有部分进度。 */
    RUNNING,
    /** 所有子运行均成功。 */
    SUCCEEDED,
    /** 没有成功子运行且存在失败/取消。 */
    FAILED,
    /** 用户显式取消批次。 */
    CANCELLED,
    /** 成功与失败/取消子运行并存。 */
    PARTIAL
}
