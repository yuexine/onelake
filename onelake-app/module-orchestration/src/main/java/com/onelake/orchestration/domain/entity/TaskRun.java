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
import org.hibernate.annotations.ColumnTransformer;

import java.time.Instant;
import java.util.UUID;

/**
 * 节点级运行实例。每个 {@link JobRun} 中的每个节点对应一条记录。
 *
 * <p>状态使用统一运行状态口径，独立于 {@code JobRun.status} 的聚合状态。
 */
@Entity
@Table(name = "task_run", schema = "orchestration")
@Getter
@Setter
public class TaskRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** 节点运行所属租户，内部回调必须同时校验该边界。 */
    @Column(nullable = false)
    private UUID tenantId;

    /** 所属 JobRun。 */
    @Column(nullable = false)
    private UUID jobRunId;

    /** 对应 PipelineTask.taskKey，也是 Dagster step key。 */
    @Column(nullable = false, length = 128)
    private String taskKey;

    /** 本次运行实际使用的不可变算子版本；普通节点为空。 */
    @Column(length = 32)
    private String operatorVersion;

    /** 节点运行状态。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TaskRunStatus status = TaskRunStatus.QUEUED;

    /** 当前累计尝试次数；节点重跑会继续递增。 */
    @Column(nullable = false)
    private int attempt = 1;

    /** 自动重试上限。 */
    @Column(nullable = false)
    private int maxRetries = 0;

    /** 持久化日志对象键或外部日志引用。 */
    @Column(length = 512)
    private String logRef;

    /** Dagster 回调携带的 step key，必须与 taskKey 一致。 */
    @Column(length = 128)
    private String dagsterStepKey;

    /** 从 JobRun 复制的数据区间左边界。 */
    private Instant dataIntervalStart;
    /** 从 JobRun 复制的数据区间右边界。 */
    private Instant dataIntervalEnd;

    /** 节点写出行数指标。 */
    private Long rowsWritten;
    /** 节点扫描字节数指标。 */
    private Long scanBytes;

    /** 节点失败原因。 */
    @Column(length = 4000)
    private String errorMsg;

    /** 节点产物位置，例如 table:FQN 或对象存储 URI。 */
    @Column(length = 512)
    private String artifactPath;

    /** 上游节点输出快照，供下游节点参数注入。 */
    @Column(columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String outputs;

    private Instant startedAt;
    private Instant finishedAt;

    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
}
