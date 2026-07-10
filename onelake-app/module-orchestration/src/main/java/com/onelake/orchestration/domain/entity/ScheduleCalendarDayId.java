package com.onelake.orchestration.domain.entity;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.UUID;

/**
 * {@link ScheduleCalendarDay} 的 JPA 复合主键值对象。
 *
 * <p>实现 {@link Serializable} 并提供无参构造、相等性和哈希语义，满足
 * {@code @IdClass} 规范，也便于 Repository 直接用“日历 + 日期”查询覆盖记录。
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode
public class ScheduleCalendarDayId implements Serializable {

    private UUID calendarId;
    private LocalDate day;

    /** 创建一个确定日历中确定日期的查询键。 */
    public ScheduleCalendarDayId(UUID calendarId, LocalDate day) {
        this.calendarId = calendarId;
        this.day = day;
    }
}
