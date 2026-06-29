package com.onelake.orchestration.service.spi;

/**
 * Execution engines supported by the pipeline runtime.
 *
 * <p>See docs/流水线模块重设计方案.md §6.3 (C3 SPI renaming).
 */
public enum EngineType {
    /** Spark SQL via Dagster run_spark_task_op. READY in P-Spark. */
    SPARK_SQL,
    /** PySpark via Dagster run_spark_task_op. READY in P-Spark (PYTHON task type still contract-only). */
    PYSPARK
}
