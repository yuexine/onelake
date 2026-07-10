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
 *
 * <p>{@code calendarId + day} 构成复合主键。同一天没有覆盖记录时，调度器按普通
 * 可调度日处理；HOLIDAY 明确跳过，WORKDAY 可覆盖外部日历的默认休息日语义。
 */
@Entity
@Table(name = "schedule_calendar_day", schema = "orchestration")
@IdClass(ScheduleCalendarDayId.class)
@Getter
@Setter
public class ScheduleCalendarDay {

    /** 所属调度日历 ID。 */
    @Id
    @Column(nullable = false)
    private UUID calendarId;

    /** 日历本地日期，不包含时分秒。 */
    @Id
    @Column(nullable = false)
    private LocalDate day;

    /** 日期类型：HOLIDAY 或 WORKDAY。 */
    @Column(nullable = false, length = 16)
    private String dayType;
}
