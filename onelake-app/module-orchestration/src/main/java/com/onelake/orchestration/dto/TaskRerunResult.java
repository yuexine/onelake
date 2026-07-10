package com.onelake.orchestration.dto;

import java.util.List;
import java.util.UUID;

/**
 * 节点重跑结果。
 *
 * @param runId 原 JobRun ID
 * @param rerunTasks 本次实际重跑的 taskKey，DOWNSTREAM 模式可能包含多个节点
 * @param dagsterRunId 新启动的 Dagster run ID
 */
public record TaskRerunResult(
        UUID runId,
        List<String> rerunTasks,
        String dagsterRunId
) {
}
