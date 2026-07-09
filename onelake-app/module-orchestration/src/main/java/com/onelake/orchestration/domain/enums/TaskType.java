package com.onelake.orchestration.domain.enums;

/**
 * 流水线 V2 节点类型。
 */
public enum TaskType {
    /** 对 Spark 产出的资产做质量门禁。 */
    QUALITY_GATE,
    /** 引用集成同步任务，用于声明运行顺序依赖。 */
    SYNC_REF,
    /** Spark SQL 节点，当前真实执行主路径。 */
    SPARK_SQL,
    /** 运行在 Spark Runtime 上的 PySpark 节点。 */
    PYSPARK
}
