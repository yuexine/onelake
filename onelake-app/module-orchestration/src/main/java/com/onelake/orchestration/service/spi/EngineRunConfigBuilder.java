package com.onelake.orchestration.service.spi;

/**
 * 为单个执行引擎构建 Dagster runConfig 片段。
 *
 * <p><b>职责边界</b>：Java 只生成 runConfig，Dagster 负责执行。
 * 实现类不得直接调用运行时引擎，不得写入 {@code modeling.*} schema，
 * 也不得产生构造 runConfig 之外的副作用。
 *
 * <p>实现类以 {@link org.springframework.stereotype.Component @Component} Bean 形式注册；
 * {@code OrchestrationService} 通过 {@link #engine()} 选择对应实现。
 */
public interface EngineRunConfigBuilder {

    /** 当前构建器服务的执行引擎。 */
    EngineType engine();

    /**
     * 构建当前执行引擎对应的 Dagster runConfig。
     *
     * <p>{@link TaskBundleContext#tasks()} 已只包含匹配当前引擎的节点，无需再次过滤。
     */
    DagsterRunConfig build(TaskBundleContext ctx);
}
