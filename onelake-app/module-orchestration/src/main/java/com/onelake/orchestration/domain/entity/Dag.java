package com.onelake.orchestration.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * DAG/流水线定义（对应 {@code orchestration.dag}）。
 *
 * <p>该对象同时承载画布定义、发布状态、执行资源和生产调度策略。调度器只处理
 * {@code enabled=true、status=PUBLISHED、dagsterJob=onelake_pipeline_run} 的记录。
 * C3 字段默认值与 V16 迁移一致，保证升级前的存量 DAG 可直接读取。
 */
@Entity
@Table(name = "dag", schema = "orchestration")
@Getter
@Setter
public class Dag {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String dagsterJob;

    @Column(columnDefinition = "jsonb", nullable = false)
    @ColumnTransformer(write = "?::jsonb")
    private String definition;     // 节点/依赖（前端 X6 画布）

    /** Spring 六字段 cron；计划点按 {@link #timezone} 解释。 */
    private String scheduleCron;

    /** 调度总开关；关闭时不进入 PipelineSchedulerService 候选集合。 */
    private Boolean enabled = true;

    private Integer version = 1;

    /** 流水线 V2 字段，用于承载画布类型、发布状态和 Spark 执行资源。 */
    private String pipelineKind;     // BLANK | ODS_DWD | MULTI_LAYER
    private String status;           // DRAFT | VALIDATED | PUBLISHED
    private String engine;           // 当前流水线主路径使用 SPARK。
    private String resourceGroup;    // 例如 spark-default。
    private String computeProfile;   // 例如 spark-small。
    /** 当前生产环境使用的不可变流水线发布版本。 */
    private UUID publishedVersionId;
    /** 当前 DEV 草稿是否包含尚未发布的变更。 */
    @Column(nullable = false)
    private Boolean hasUnpublishedChanges = false;
    /** 业务数据区间粒度：HOUR、DAY 或 MONTH。 */
    private String partitionGrain = "DAY";

    /** DAG 业务时区；cron、日历日期、logical_date 和 DST 计算均以此为准。 */
    @Column(nullable = false, length = 64)
    private String timezone = "Asia/Shanghai";

    /** 是否在重新启用或发现 logical_date 缺口时自动补历史周期。 */
    @Column(nullable = false)
    private Boolean catchup = false;

    /** DAG 全部触发来源合计允许的 QUEUED/RUNNING JobRun 上限。 */
    @Column(nullable = false)
    private Integer maxActiveRuns = 1;

    /** 同一 scheduler tick 内的触发优先级；数值越大越先处理。 */
    @Column(nullable = false)
    private Integer priority = 5;

    /** 计划完成时限，供后续 SLA 监控计算。 */
    private Integer slaMinutes;
    /** 单次运行超时阈值，单位为分钟。 */
    private Integer timeoutMinutes;

    /** 调度运行模式：NORMAL、DRY_RUN 或 FROZEN。 */
    @Column(nullable = false, length = 16)
    private String scheduleMode = "NORMAL";

    /** 首次运行失败后允许创建的 DAG 级 AUTO_RETRY 运行数量。 */
    @Column(nullable = false)
    private Integer runRetryCount = 0;

    /** DAG 级失败自动重跑间隔，单位为秒。 */
    @Column(nullable = false)
    private Integer runRetryIntervalSeconds = 0;

    /** 错过计划点的处理策略元数据：FIRE_ONCE 或 SKIP。 */
    @Column(nullable = false, length = 16)
    private String misfirePolicy = "FIRE_ONCE";

    /** 依赖或并发阻塞的计划点最多等待多久；超时后保留审计记录但不再触发。 */
    @Column(nullable = false)
    private Integer dependencyWaitTimeoutMinutes = 1440;

    /** 可选的租户调度日历；命中 HOLIDAY 的计划点不触发。 */
    private UUID calendarId;
    /** 调度有效期左边界，包含该时刻。 */
    private Instant scheduleStart;
    /** 调度有效期右边界，包含该时刻。 */
    private Instant scheduleEnd;

    /** DAG 创建时间；无运行历史的 catchup 以此作为最早扫描游标。 */
    private Instant createdAt = Instant.now();
}
