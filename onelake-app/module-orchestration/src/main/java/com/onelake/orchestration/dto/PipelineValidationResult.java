package com.onelake.orchestration.dto;

import java.util.List;
import java.util.UUID;

/**
 * L1 + L2 validation result for a pipeline (§6.7 of design doc).
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

    public record GraphError(
            String level,        // ERROR | WARN
            String code,
            String message,
            List<String> taskKeys
    ) {}
}
