package com.onelake.orchestration.domain.enums;

/**
 * DAG 或流水线运行触发类型。
 */
public enum TriggerType {
    /** 调度器按 cron 表达式触发。 */
    CRON,
    /** 用户手动触发。 */
    MANUAL,
    /** 由上游事件触发。 */
    EVENT,
    /** 业务日期回填派发触发。 */
    BACKFILL,
    /** DAG 级失败策略创建的新 JobRun；与节点级 M1 重试分开计数。 */
    AUTO_RETRY
}
