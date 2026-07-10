package com.onelake.orchestration.domain.entity;

import com.onelake.orchestration.domain.enums.DagStatus;
import com.onelake.orchestration.domain.enums.TriggerType;
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
 * 运行实例（对应《技术初始化文档》§7.3 orchestration.job_run）。
 */
@Entity
@Table(name = "job_run", schema = "orchestration")
@Getter
@Setter
public class JobRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID dagId;

    private String dagsterRunId;

    /** 实际启动的 Dagster job；GRAPH 模式按流水线生成 job，不能再只从 dag 读取。 */
    private String dagsterJob;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TriggerType triggerType = TriggerType.MANUAL;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DagStatus status = DagStatus.QUEUED;

    private Instant logicalDate;
    private Instant dataIntervalStart;
    private Instant dataIntervalEnd;
    private UUID backfillId;

    /** M2 运行策略；默认值与 V16 迁移保持一致。 */
    @Column(nullable = false, length = 16)
    private String runMode = "NORMAL";

    @Column(nullable = false)
    private Boolean slaMissed = false;

    @Column(nullable = false)
    private Integer priority = 5;

    private Instant startedAt;
    private Instant finishedAt;
    private UUID triggeredBy;
    private String triggeredByName;
    private Instant updatedAt = Instant.now();
}
