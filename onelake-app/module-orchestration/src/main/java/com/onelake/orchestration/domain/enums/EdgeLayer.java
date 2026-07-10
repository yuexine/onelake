package com.onelake.orchestration.domain.enums;

/**
 * {@code pipeline_task_edge} 的边类型。
 *
 * <p>用于区分流水线内部任务依赖和未来跨执行引擎边界。
 * <ul>
 *   <li>{@code PIPELINE}：Spark 流水线内部的任务顺序与数据流连线。</li>
 *   <li>{@code CROSS_ENGINE}：预留给未来外部运行时/跨引擎边界。</li>
 * </ul>
 */
public enum EdgeLayer {
    /** 同一流水线内部的数据流/执行依赖。 */
    PIPELINE,
    /** 预留的跨运行时或跨引擎边界。 */
    CROSS_ENGINE
}
