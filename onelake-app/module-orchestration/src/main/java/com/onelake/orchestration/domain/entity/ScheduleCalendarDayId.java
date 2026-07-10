package com.onelake.orchestration.domain.entity;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 调度日历日期的复合主键。
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode
public class ScheduleCalendarDayId implements Serializable {

    private UUID calendarId;
    private LocalDate day;

    public ScheduleCalendarDayId(UUID calendarId, LocalDate day) {
        this.calendarId = calendarId;
        this.day = day;
    }
}
