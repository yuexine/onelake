package com.onelake.integration.domain.entity;

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
 * CDC 实时采集任务（对应《技术初始化文档》§7.9 审查补全表 integration.cdc_task）。
 *
 * <p>每个 CdcTask 绑定一个源表，通过 Debezium/Flink CDC 订阅 binlog，
 * 写入 Kafka topic 供下游消费。
 */
@Entity
@Table(name = "cdc_task", schema = "integration")
@Getter
@Setter
public class CdcTask {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private UUID sourceId;

    @Column(nullable = false)
    private String sourceName;

    @Column(nullable = false)
    private String tableName;         // 监听的源表（库.表）

    @Column(nullable = false)
    private String topicName;         // Kafka topic（通常 = onelake.cdc.<tenant>.<table>）

    private String checkpoint;        // 最近位点（binlog.000128:4456）

    @Enumerated(EnumType.STRING)
    private CdcStatus status = CdcStatus.DRAFT;

    private Instant startedAt;

    private Instant createdAt = Instant.now();

    public enum CdcStatus { DRAFT, RUNNING, PAUSED, FAILED }
}
