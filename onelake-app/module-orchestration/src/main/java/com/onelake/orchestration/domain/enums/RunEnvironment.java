package com.onelake.orchestration.domain.enums;

/**
 * 流水线运行环境。
 *
 * <p>PROD 只执行不可变发布快照；DEV 只用于手动试跑当前实时草稿。</p>
 */
public enum RunEnvironment {
    PROD,
    DEV
}
