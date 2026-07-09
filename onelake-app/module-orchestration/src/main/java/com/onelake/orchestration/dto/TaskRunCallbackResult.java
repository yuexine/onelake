package com.onelake.orchestration.dto;

import com.onelake.orchestration.domain.enums.TaskRunStatus;

public record TaskRunCallbackResult(
        boolean applied,
        TaskRunStatus currentStatus
) {
}
