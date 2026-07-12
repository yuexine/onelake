package com.onelake.orchestration.domain.enums;

/**
 * 流水线 V2 节点类型。
 */
public enum TaskType {
    /** 对 Spark 产出的资产做质量门禁；为保持既有运行路径仍归入执行类。 */
    QUALITY_GATE(TaskCategory.EXEC),
    /** 引用集成同步任务，用于声明运行顺序依赖。 */
    SYNC_REF(TaskCategory.OBSERVE),
    /** Spark SQL 节点，当前真实执行主路径。 */
    SPARK_SQL(TaskCategory.EXEC),
    /** 运行在 Spark Runtime 上的 PySpark 节点。 */
    PYSPARK(TaskCategory.EXEC),
    /** 通过受限 Trino 会话执行 SQL。 */
    TRINO_SQL(TaskCategory.EXEC),
    /** 在受限脚本沙箱中执行 Python。 */
    PYTHON(TaskCategory.EXEC),
    /** 在受限脚本沙箱中执行 Shell。 */
    SHELL(TaskCategory.EXEC),
    /** 根据表达式选择后续分支。 */
    BRANCH(TaskCategory.CONTROL),
    /** 按条件决定后续节点是否继续。 */
    CONDITION(TaskCategory.CONTROL),
    /** 轮询外部状态直到满足条件。 */
    SENSOR(TaskCategory.OBSERVE),
    /** 等待指定时间或时间窗口。 */
    WAIT(TaskCategory.OBSERVE),
    /** 触发并等待另一条流水线。 */
    SUB_PIPELINE(TaskCategory.CONTROL),
    /** 发送运行通知。 */
    NOTIFY(TaskCategory.OBSERVE),
    /** 对运行结果执行断言。 */
    ASSERTION(TaskCategory.OBSERVE);

    private final TaskCategory category;

    TaskType(TaskCategory category) {
        this.category = category;
    }

    public TaskCategory category() {
        return category;
    }
}
