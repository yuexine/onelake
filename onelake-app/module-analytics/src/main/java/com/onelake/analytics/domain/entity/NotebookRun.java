package com.onelake.analytics.domain.entity;

import com.onelake.analytics.domain.enums.RunStatus;
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
import org.hibernate.annotations.ColumnTransformer;

import java.time.Instant;
import java.util.UUID;

/**
 * Notebook 调度执行实例（句柄 dagster_run_id 关联 Dagster run）。
 */
@Entity
@Table(name = "notebook_run", schema = "analytics")
@Getter
@Setter
public class NotebookRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID notebookId;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(length = 64)
    private String dagsterRunId;

    @Column(columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String params;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RunStatus status = RunStatus.PENDING;

    @Column(length = 512)
    private String outputHtml;

    private Instant startedAt;

    private Instant finishedAt;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();
}
