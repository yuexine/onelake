package com.onelake.orchestration.dto;

import com.onelake.orchestration.domain.entity.PipelineDependencyWait;

import java.time.Instant;
import java.util.UUID;

/** 调度计划点因依赖或并发阻塞产生的等待审计记录。 */
public record ScheduleWaitDTO(
        UUID id,
        UUID dagId,
        Instant logicalDate,
        Instant scheduledAt,
        String waitReason,
        String status,
        String lastBlockers,
        Instant expiresAt,
        Instant resolvedAt,
        Instant createdAt,
        Instant updatedAt
) {
    public static ScheduleWaitDTO of(PipelineDependencyWait wait) {
        return new ScheduleWaitDTO(
                wait.getId(),
                wait.getDagId(),
                wait.getLogicalDate(),
                wait.getScheduledAt(),
                wait.getWaitReason(),
                wait.getStatus(),
                wait.getLastBlockers(),
                wait.getExpiresAt(),
                wait.getResolvedAt(),
                wait.getCreatedAt(),
                wait.getUpdatedAt());
    }
}
