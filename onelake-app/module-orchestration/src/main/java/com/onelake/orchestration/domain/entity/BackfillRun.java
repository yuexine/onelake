package com.onelake.orchestration.domain.entity;

import com.onelake.orchestration.domain.enums.BackfillRunStatus;
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
 * 单个业务日期的回填派发明细。
 */
@Entity
@Table(name = "backfill_run", schema = "orchestration")
@Getter
@Setter
public class BackfillRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private UUID backfillId;

    @Column(nullable = false)
    private UUID dagId;

    @Column(nullable = false)
    private Instant logicalDate;

    @Column(nullable = false)
    private Instant dataIntervalStart;

    @Column(nullable = false)
    private Instant dataIntervalEnd;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BackfillRunStatus status = BackfillRunStatus.QUEUED;

    private UUID jobRunId;

    @Column(length = 4000)
    private String errorMsg;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();
}
