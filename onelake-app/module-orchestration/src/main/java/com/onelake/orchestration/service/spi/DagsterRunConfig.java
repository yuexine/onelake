package com.onelake.orchestration.service.spi;

import java.util.Map;

/**
 * 单个执行引擎贡献给 Dagster run 的不可变描述。
 *
 * <p>Java 控制面只负责构建该对象；Dagster op 原样消费它。Java 不直接执行运行时引擎。
 *
 * @param jobName Dagster job 名称，例如 {@code onelake_pipeline_run}
 * @param opConfig 发送给 GraphQL launchRun 的完整 Dagster runConfigData，
 *                 例如 {@code ops -> run_spark_task_op -> config -> { tasks, resource_profile }}
 */
public record DagsterRunConfig(String jobName, Map<String, Object> opConfig) {

    public DagsterRunConfig {
        opConfig = opConfig == null ? Map.of() : Map.copyOf(opConfig);
    }
}
