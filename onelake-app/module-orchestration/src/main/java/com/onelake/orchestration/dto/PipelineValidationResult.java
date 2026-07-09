package com.onelake.orchestration.dto;

import java.util.List;
import java.util.UUID;

/**
 * 流水线 L1 + L2 校验结果。
 */
public record PipelineValidationResult(
        UUID pipelineId,
        boolean valid,
        List<TaskValidation> taskResults,
        List<GraphError> graphErrors
) {

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
