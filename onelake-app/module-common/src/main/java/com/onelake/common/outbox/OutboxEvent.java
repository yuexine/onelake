package com.onelake.common.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbox 事件（对应《技术初始化文档》§6.7 与 §7.1 common.outbox_event）。
 * 业务写操作同事务落 outbox，由定时任务 / Dagster Sensor 消费 → 实现跨模块最终一致。
 */
@Entity
@Table(name = "outbox_event", schema = "common")
@Getter
@Setter
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String eventType;

    private String aggregateId;

    @Column(columnDefinition = "jsonb")
    private String payload;

    @Enumerated(EnumType.STRING)
    private Status status = Status.PENDING;

    private Instant occurredAt = Instant.now();

    public enum Status {
        PENDING, SENT, FAILED
    }
}
