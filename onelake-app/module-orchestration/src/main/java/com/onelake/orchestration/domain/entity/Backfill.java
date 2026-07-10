package com.onelake.orchestration.domain.entity;

import com.onelake.orchestration.domain.enums.BackfillStatus;
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
 * Scheduler-managed 业务日期回填批次。
 *
 * <p>批次负责冻结范围、粒度、时区、并发限制和聚合进度；每个具体业务周期由
 * {@link BackfillRun} 单独排队。手工范围回填与 C3 catchup 共用该对象和状态机。
 */
@Entity
@Table(name = "backfill", schema = "orchestration")
@Getter
@Setter
public class Backfill {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** 租户边界，用于异步线程恢复 TenantContext。 */
    @Column(nullable = false)
    private UUID tenantId;

    /** 被补跑的 DAG。 */
    @Column(nullable = false)
    private UUID dagId;

    /** 批次展示范围的首个 logical_date。 */
    @Column(nullable = false)
    private Instant rangeStart;

    /** 批次展示范围的最后一个 logical_date，包含该周期。 */
    @Column(nullable = false)
    private Instant rangeEnd;

    /** 业务区间粒度：HOUR、DAY 或 MONTH。 */
    @Column(nullable = false, length = 16)
    private String grain = "DAY";

    /** 创建回填时冻结的业务时区，供窗口展开和后续分批派发复用。 */
    @Column(nullable = false, length = 64)
    private String timezone = "Asia/Shanghai";

    /** 由子明细聚合得到的批次状态。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BackfillStatus status = BackfillStatus.QUEUED;

    /** 计划持久化的业务周期总数。 */
    @Column(nullable = false)
    private Integer totalRuns = 0;

    /** 已成功子运行数。 */
    @Column(nullable = false)
    private Integer succeededRuns = 0;

    /** 失败或取消子运行数。 */
    @Column(nullable = false)
    private Integer failedRuns = 0;

    /** 本批次内部最大并行子运行数；还会受到 DAG max_active_runs 限制。 */
    @Column(nullable = false)
    private Integer maxParallel = 1;

    /** 创建批次的用户 ID，异步派发时用于恢复审计身份。 */
    private UUID createdBy;

    /** 创建人显示名。 */
    @Column(length = 128)
    private String createdByName;

    /** 批次创建时间。 */
    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    /** 最近一次派发或进度聚合时间。 */
    @Column(nullable = false)
    private Instant updatedAt = Instant.now();
}
