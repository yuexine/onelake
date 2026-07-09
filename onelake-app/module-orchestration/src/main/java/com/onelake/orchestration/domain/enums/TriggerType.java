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
    EVENT
}
