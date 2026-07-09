package com.onelake.orchestration.domain.enums;

/**
 * 算子可见范围。
 */
public enum OperatorScope {
    /** 系统内置算子。 */
    BUILTIN,
    /** 租户自定义算子。 */
    CUSTOM,
    /** 租户私有算子。 */
    TENANT_PRIVATE
}
