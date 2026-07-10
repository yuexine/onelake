package com.onelake.orchestration.dto;

import com.onelake.orchestration.domain.enums.TaskRunStatus;

/**
 * 节点状态回调处理结果。
 *
 * @param applied 回调是否推进了本地状态；重复或倒退回调为 false
 * @param currentStatus 处理后的权威本地状态
 */
public record TaskRunCallbackResult(
        boolean applied,
        TaskRunStatus currentStatus
) {
}
