package com.onelake.orchestration.domain.enums;

/**
 * 单个 {@code pipeline_task} 的编译状态。
 */
public enum TaskCompileStatus {
    /** 尚未完成编译校验。 */
    DRAFT,
    /** 节点配置和运行契约有效。 */
    VALIDATED,
    /** 编译失败，compileError 保存原因。 */
    FAILED
}
