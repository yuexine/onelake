package com.onelake.orchestration.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 调度日历中的单日覆盖：{@code HOLIDAY} 或 {@code WORKDAY}。
 */
@Entity
@Table(name = "schedule_calendar_day", schema = "orchestration")
@IdClass(ScheduleCalendarDayId.class)
@Getter
@Setter
public class ScheduleCalendarDay {

    @Id
    @Column(nullable = false)
    private UUID calendarId;

    @Id
    @Column(nullable = false)
    private LocalDate day;

    @Column(nullable = false, length = 16)
    private String dayType;
}
