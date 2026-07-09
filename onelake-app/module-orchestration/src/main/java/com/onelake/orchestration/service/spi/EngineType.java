package com.onelake.orchestration.service.spi;

/**
 * 流水线运行时支持的执行引擎。
 */
public enum EngineType {
    /** 通过 Dagster {@code run_spark_task_op} 执行 Spark SQL。 */
    SPARK_SQL,
    /** 通过 Dagster {@code run_spark_task_op} 执行 PySpark。 */
    PYSPARK
}
