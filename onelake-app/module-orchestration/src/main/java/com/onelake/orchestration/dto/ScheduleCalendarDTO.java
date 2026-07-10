package com.onelake.orchestration.dto;

import com.onelake.orchestration.domain.entity.ScheduleCalendar;

import java.time.Instant;
import java.util.UUID;

/** 调度配置选择器使用的租户日历摘要。 */
public record ScheduleCalendarDTO(
        UUID id,
        String name,
        String timezone,
        Instant createdAt
) {
    public static ScheduleCalendarDTO of(ScheduleCalendar calendar) {
        return new ScheduleCalendarDTO(
                calendar.getId(),
                calendar.getName(),
                calendar.getTimezone(),
                calendar.getCreatedAt());
    }
}
