package com.onelake.common.task;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "running_task",
    schema = "common",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_running_task_ref",
        columnNames = {"tenant_id", "ref_type", "ref_id"}
    )
)
@Getter
@Setter
public class RunningTask {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    private UUID userId;

    @Column(nullable = false, length = 32)
    private String sourceModule;

    @Column(nullable = false, length = 32)
    private String taskType;

    @Column(nullable = false, length = 64)
    private String refType;

    @Column(nullable = false, length = 128)
    private String refId;

    @Column(length = 128)
    private String parentRefId;

    @Column(nullable = false, length = 256)
    private String title;

    @Column(nullable = false, length = 16)
    private String status;

    private Integer progress;

    @Column(length = 64)
    private String phase;

    @Column(length = 512)
    private String detail;

    @Column(length = 64)
    private String errorCode;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @Column(length = 512)
    private String link;

    @Column(nullable = false)
    private Boolean cancellable = false;

    @Column(length = 512)
    private String cancelEndpoint;

    @Column(nullable = false)
    private Instant startedAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    private Instant finishedAt;

    private Instant expiresAt;

    private Instant dismissedAt;
}
