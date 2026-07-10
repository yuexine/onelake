package com.onelake.orchestration.dto;

import java.util.List;
import java.util.UUID;

/**
 * 流水线 L1 + L2 校验结果。
 *
 * @param pipelineId 被校验流水线
 * @param valid 是否不存在阻断错误
 * @param taskResults 节点级 L1 校验结果
 * @param graphErrors 图级 L2 错误和告警
 */
public record PipelineValidationResult(
        UUID pipelineId,
        boolean valid,
        List<TaskValidation> taskResults,
        List<GraphError> graphErrors
) {

    /** 节点配置和编译校验结果。 */
    public record TaskValidation(
            String taskKey,
            String taskType,
            boolean valid,
            String errorMessage,
            String errorCode
    ) {}

    /**
     * 图级校验错误。
     */
    public record GraphError(
            String level,        // 错误级别，取值为 ERROR | WARN。
            String code,
            String message,
            List<String> taskKeys
    ) {}
}
