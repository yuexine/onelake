package com.onelake.orchestration.domain.entity;

import com.onelake.orchestration.domain.enums.TaskRunStatus;
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
 * Per-task run instance. One row per task per {@link JobRun}.
 *
 * <p>Status uses the unified RunStatus (C8 — QUEUED|RUNNING|SUCCEEDED|FAILED|CANCELLED),
 * independent of the aggregate {@code JobRun.status}.
 */
@Entity
@Table(name = "task_run", schema = "orchestration")
@Getter
@Setter
public class TaskRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private UUID jobRunId;

    @Column(nullable = false, length = 128)
    private String taskKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TaskRunStatus status = TaskRunStatus.QUEUED;

    @Column(nullable = false)
    private int attempt = 1;

    @Column(nullable = false)
    private int maxRetries = 0;

    @Column(length = 512)
    private String logRef;

    @Column(length = 128)
    private String dagsterStepKey;

    private Instant dataIntervalStart;
    private Instant dataIntervalEnd;

    private Long rowsWritten;
    private Long scanBytes;

    @Column(length = 4000)
    private String errorMsg;

    @Column(length = 512)
    private String artifactPath;

    private Instant startedAt;
    private Instant finishedAt;

    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
}
