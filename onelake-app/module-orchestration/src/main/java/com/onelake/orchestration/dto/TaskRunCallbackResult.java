package com.onelake.orchestration.dto;

import com.onelake.orchestration.domain.enums.TaskRunStatus;

/**
 * 节点状态回调处理结果。
 */
public record TaskRunCallbackResult(
        boolean applied,
        TaskRunStatus currentStatus
) {
}
