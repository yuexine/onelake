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
 * 租户级调度日历，存放工作日和节假日覆盖规则。
 */
@Entity
@Table(name = "schedule_calendar", schema = "orchestration")
@Getter
@Setter
public class ScheduleCalendar {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(nullable = false, length = 64)
    private String timezone = "Asia/Shanghai";

    @Column(nullable = false)
    private Instant createdAt = Instant.now();
}
