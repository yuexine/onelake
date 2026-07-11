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
 * DAG 运行实例（对应 {@code orchestration.job_run}）。
 *
 * <p>JobRun 是调度、手动触发和回填触发共用的运行事实表。创建时冻结业务时间、
 * 触发来源和运行模式，使后续 DAG 配置变化不会重解释历史运行。
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

    /** Dagster 控制面返回的运行 ID，用于状态同步、日志和终止操作。 */
    private String dagsterRunId;

    /** 实际启动的 Dagster job；GRAPH 模式按流水线生成 job，不能再只从 dag 读取。 */
    private String dagsterJob;

    /** 触发来源：MANUAL、CRON、BACKFILL 等。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TriggerType triggerType = TriggerType.MANUAL;

    /** 统一运行状态；QUEUED/RUNNING 为非终态并占用 max_active_runs。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DagStatus status = DagStatus.QUEUED;

    /** 业务周期标识，固定等于 dataIntervalStart。 */
    private Instant logicalDate;
    /** 本次运行负责处理的数据区间左边界。 */
    private Instant dataIntervalStart;
    /** 本次运行负责处理的数据区间右边界。 */
    private Instant dataIntervalEnd;
    /** 非空时表示该运行由某个 scheduler-managed backfill 批次产生。 */
    private UUID backfillId;

    /** 本次运行绑定的不可变流水线发布版本；DEV 草稿试跑可为空。 */
    private UUID pipelineVersionId;

    /** 创建运行时冻结的业务时区，避免 DAG 配置变更重解释历史业务日期。 */
    @Column(nullable = false, length = 64)
    private String timezone = "Asia/Shanghai";

    /** M2 运行策略及环境标记：NORMAL、DRY_RUN 或 DEV；DEV 不计入生产运行口径。 */
    @Column(nullable = false, length = 16)
    private String runMode = "NORMAL";

    /** SLA 检查是否已判定该运行违约。 */
    @Column(nullable = false)
    private Boolean slaMissed = false;

    /** 创建运行时冻结的 DAG 优先级，供运行队列和观测使用。 */
    @Column(nullable = false)
    private Integer priority = 5;

    /** 自动重跑直接来源；普通运行为空，形成可审计的失败重跑链。 */
    private UUID retrySourceRunId;

    /** DAG 级自动重跑序号；首次运行为 0，第一条 AUTO_RETRY 为 1。 */
    @Column(nullable = false)
    private Integer runRetryAttempt = 0;

    /** 失败运行已被自动重跑派发器领取或确认达到次数上限的时间。 */
    private Instant retryDispatchedAt;

    /** OneLake 接受并开始创建运行的时间。 */
    private Instant startedAt;
    /** 运行进入终态的时间。 */
    private Instant finishedAt;
    /** 触发用户 ID；系统调度可能为空。 */
    private UUID triggeredBy;
    /** 触发人显示名；用于审计和回填线程恢复租户身份。 */
    private String triggeredByName;
    /** 最近一次状态或外部运行信息同步时间。 */
    private Instant updatedAt = Instant.now();
}
