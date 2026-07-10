package com.onelake.orchestration.domain.enums;

import java.util.Locale;

/**
 * 流水线调度运行模式。
 *
 * <p>DRY_RUN 会产生成功的父子运行记录但不执行数据任务，因此可满足跨周期依赖；
 * FROZEN 不产生周期运行，并且即使存在历史成功运行也必须继续阻塞下游依赖。</p>
 */
public enum ScheduleMode {
    NORMAL,
    DRY_RUN,
    FROZEN;

    /** 对空值和历史未知值保持 NORMAL 兼容。 */
    public static ScheduleMode from(String value) {
        if (value == null || value.isBlank()) {
            return NORMAL;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return NORMAL;
        }
    }

    /**
     * 跨流水线/跨周期依赖的统一判定口径。
     *
     * <p>DRY_RUN 对应的 JobRun 会进入 SUCCEEDED，因而与 NORMAL 成功运行一样满足依赖；
     * FROZEN 始终返回未就绪，供 C2 依赖服务直接复用。</p>
     */
    public boolean satisfiesDependency(DagStatus upstreamRunStatus) {
        return this != FROZEN && upstreamRunStatus == DagStatus.SUCCEEDED;
    }
}
