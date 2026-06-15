package com.onelake.integration.domain.entity;

import com.onelake.integration.domain.enums.SyncMode;
import com.onelake.integration.domain.enums.TaskStatus;
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
 * 同步任务（对应《技术初始化文档》§7.2 integration.sync_task）。
 */
@Entity
@Table(name = "sync_task", schema = "integration")
@Getter
@Setter
public class SyncTask {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private UUID sourceId;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SyncMode mode;

    @Column(nullable = false)
    private String targetTable;

    @Column(columnDefinition = "jsonb")
    private String fieldMapping;

    private String airbyteConnectionId;

    private String scheduleCron;

    private Integer rateLimit;          // rows/s

    private Integer dirtyThreshold = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TaskStatus status = TaskStatus.DRAFT;

    private Instant createdAt = Instant.now();
}
