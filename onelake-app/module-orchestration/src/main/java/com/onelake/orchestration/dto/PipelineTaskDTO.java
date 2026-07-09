package com.onelake.orchestration.dto;

import com.onelake.orchestration.domain.entity.PipelineTask;
import com.onelake.orchestration.domain.enums.TaskCompileStatus;
import com.onelake.orchestration.domain.enums.TaskType;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * {@link PipelineTask} 的接口响应投影。
 */
public record PipelineTaskDTO(
        UUID id,
        UUID dagId,
        String taskKey,
        TaskType taskType,
        String name,
        String engine,
        String targetFqn,
        UUID modelId,
        UUID syncTaskId,
        Map<String, Object> config,
        TaskCompileStatus compileStatus,
        String compileError,
        Boolean executable,
        Integer positionX,
        Integer positionY,
        Instant createdAt,
        Instant updatedAt
) {
    public static PipelineTaskDTO of(PipelineTask t) {
        @SuppressWarnings("unchecked")
        Map<String, Object> cfg = t.getConfig() == null || "{}".equals(t.getConfig().trim())
                ? Map.of()
                : com.onelake.common.util.JsonUtil.fromJson(t.getConfig(), Map.class);
        return new PipelineTaskDTO(
                t.getId(),
                t.getDagId(),
                t.getTaskKey(),
                t.getTaskType(),
                t.getName(),
                t.getEngine(),
                t.getTargetFqn(),
                t.getModelId(),
                t.getSyncTaskId(),
                cfg,
                t.getCompileStatus(),
                t.getCompileError(),
                t.getExecutable(),
                t.getPositionX(),
                t.getPositionY(),
                t.getCreatedAt(),
                t.getUpdatedAt()
        );
    }
}
