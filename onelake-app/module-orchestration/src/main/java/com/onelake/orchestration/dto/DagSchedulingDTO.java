package com.onelake.orchestration.dto;

import com.onelake.orchestration.domain.entity.Dag;

import java.time.Instant;
import java.util.UUID;

/** 流水线生产调度策略的稳定 API 投影。 */
public record DagSchedulingDTO(
        UUID dagId,
        String timezone,
        Boolean catchup,
        Integer maxActiveRuns,
        Integer priority,
        String scheduleMode,
        String misfirePolicy,
        Integer dependencyWaitTimeoutMinutes,
        Integer slaMinutes,
        Integer timeoutMinutes,
        Integer runRetryCount,
        Integer runRetryIntervalSeconds,
        UUID calendarId,
        Instant scheduleStart,
        Instant scheduleEnd
) {
    public static DagSchedulingDTO of(Dag dag) {
        return new DagSchedulingDTO(
                dag.getId(),
                dag.getTimezone(),
                dag.getCatchup(),
                dag.getMaxActiveRuns(),
                dag.getPriority(),
                dag.getScheduleMode(),
                dag.getMisfirePolicy(),
                dag.getDependencyWaitTimeoutMinutes(),
                dag.getSlaMinutes(),
                dag.getTimeoutMinutes(),
                dag.getRunRetryCount(),
                dag.getRunRetryIntervalSeconds(),
                dag.getCalendarId(),
                dag.getScheduleStart(),
                dag.getScheduleEnd());
    }
}
