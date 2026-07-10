package com.onelake.orchestration.domain.enums;

/**
 * 算子分类。
 */
public enum OperatorCategory {
    /** 输入或源数据引用。 */
    INPUT,
    /** 通用数据变换。 */
    TRANSFORM,
    /** 数据治理处理。 */
    GOVERN,
    /** 标准化处理。 */
    STANDARD,
    /** 数据脱敏。 */
    MASK,
    /** 数据加密。 */
    ENCRYPT,
    /** 聚合计算。 */
    AGG,
    /** 多输入连接。 */
    JOIN,
    /** 质量门禁。 */
    QUALITY_GATE,
    /** 数据输出或落表。 */
    OUTPUT
}
