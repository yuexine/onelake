package com.onelake.orchestration.dto;

import java.util.List;
import java.util.UUID;

/**
 * 节点重跑结果。
 */
public record TaskRerunResult(
        UUID runId,
        List<String> rerunTasks,
        String dagsterRunId
) {
}
