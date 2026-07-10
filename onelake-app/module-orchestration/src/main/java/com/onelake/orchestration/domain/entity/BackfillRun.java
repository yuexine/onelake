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
 * 单个业务周期的回填派发明细。
 *
 * <p>该对象是 M1 可恢复队列中的最小工作单元。创建批次时先以 QUEUED 持久化，
 * 获得并发槽位后再产生 BACKFILL 类型 JobRun，并通过 {@code jobRunId} 关联状态。
 */
@Entity
@Table(name = "backfill_run", schema = "orchestration")
@Getter
@Setter
public class BackfillRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** 所属租户。 */
    @Column(nullable = false)
    private UUID tenantId;

    /** 所属回填批次。 */
    @Column(nullable = false)
    private UUID backfillId;

    /** 被补跑的 DAG。 */
    @Column(nullable = false)
    private UUID dagId;

    /** 业务周期标识，固定等于 dataIntervalStart。 */
    @Column(nullable = false)
    private Instant logicalDate;

    /** 数据区间左边界。 */
    @Column(nullable = false)
    private Instant dataIntervalStart;

    /** 数据区间右边界。 */
    @Column(nullable = false)
    private Instant dataIntervalEnd;

    /** 队列明细状态；RUNNING 表示已经开始或正在创建对应 JobRun。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BackfillRunStatus status = BackfillRunStatus.QUEUED;

    /** 派发成功后关联的真实 JobRun ID。 */
    private UUID jobRunId;

    /** 派发或子运行失败原因，限制为数据库列允许的长度。 */
    @Column(length = 4000)
    private String errorMsg;

    /** 队列明细创建时间。 */
    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    /** 最近一次派发、状态同步或错误更新时间。 */
    @Column(nullable = false)
    private Instant updatedAt = Instant.now();
}
