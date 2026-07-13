package com.onelake.orchestration.dto;

/** Whether this request inserted a notification or matched an idempotent retry. */
public record PipelineNodeNotificationResult(boolean created) {
}
