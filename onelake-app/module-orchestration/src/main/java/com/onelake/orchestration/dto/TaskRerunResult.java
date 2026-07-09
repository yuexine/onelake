package com.onelake.orchestration.dto;

import java.util.List;
import java.util.UUID;

public record TaskRerunResult(
        UUID runId,
        List<String> rerunTasks,
        String dagsterRunId
) {
}
