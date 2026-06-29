package com.onelake.orchestration.domain.enums;

/**
 * Pipeline v2 task types.
 * See docs/流水线模块重设计方案.md §6.1.
 */
public enum TaskType {
    /** Quality gate over a Spark-produced asset. */
    QUALITY_GATE,
    /** Reference to an integration sync task; declares run-order dependency. */
    SYNC_REF,
    /** Spark SQL task (real execution path in P-Spark). */
    SPARK_SQL,
    /** PySpark task on the Spark runtime. */
    PYSPARK
}
