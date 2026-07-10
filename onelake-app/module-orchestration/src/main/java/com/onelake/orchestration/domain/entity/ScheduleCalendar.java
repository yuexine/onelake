package com.onelake.orchestration.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * 租户级调度日历主对象。
 *
 * <p>日历本身保存名称和解释日期所需的时区，具体工作日/节假日覆盖记录存放在
 * {@link ScheduleCalendarDay}。DAG 通过 {@code calendar_id} 选择性绑定一个日历。
 */
@Entity
@Table(name = "schedule_calendar", schema = "orchestration")
@Getter
@Setter
public class ScheduleCalendar {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** 日历所属租户；日历配置不得跨租户绑定。 */
    @Column(nullable = false)
    private UUID tenantId;

    /** 运维侧可读名称，例如“中国法定节假日”。 */
    @Column(nullable = false, length = 128)
    private String name;

    /** 日历日期的业务时区；当前 C3 触发判断使用 DAG 本地日期。 */
    @Column(nullable = false, length = 64)
    private String timezone = "Asia/Shanghai";

    /** 日历创建时间。 */
    @Column(nullable = false)
    private Instant createdAt = Instant.now();
}
