package com.onelake.orchestration.dto;

import com.onelake.orchestration.domain.entity.TaskRun;
import com.onelake.orchestration.domain.enums.TaskRunStatus;

import java.time.Instant;
import java.util.UUID;

public record TaskRunDTO(
        UUID id,
        UUID jobRunId,
        String taskKey,
        TaskRunStatus status,
        Long rowsWritten,
        Long scanBytes,
        String errorMsg,
        String artifactPath,
        Instant startedAt,
        Instant finishedAt
) {
    public static TaskRunDTO of(TaskRun t) {
        return new TaskRunDTO(
                t.getId(), t.getJobRunId(), t.getTaskKey(), t.getStatus(),
                t.getRowsWritten(), t.getScanBytes(), t.getErrorMsg(), t.getArtifactPath(),
                t.getStartedAt(), t.getFinishedAt()
        );
    }
}
