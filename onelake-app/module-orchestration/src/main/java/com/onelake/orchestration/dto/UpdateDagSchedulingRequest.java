package com.onelake.orchestration.dto;

import java.time.Instant;
import java.util.UUID;

/** 更新流水线生产调度策略的请求。可选阈值、日历和窗口传 null 表示清空。 */
public record UpdateDagSchedulingRequest(
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
}
