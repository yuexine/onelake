package com.onelake.orchestration.domain.enums;

/**
 * 算子生命周期状态。
 */
public enum OperatorStatus {
    /** 可正常使用。 */
    ACTIVE,
    /** 已废弃，不建议继续安装或使用。 */
    DEPRECATED
}
