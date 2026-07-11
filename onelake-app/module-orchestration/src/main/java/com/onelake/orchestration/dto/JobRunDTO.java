package com.onelake.orchestration.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * DAG 运行实例接口响应对象。
 *
 * <p>该投影同时覆盖通用 DAG、流水线 CRON、手动运行和回填子运行；业务时间字段在
 * 旧通用 DAG 路径中允许为空。
 *
 * @param id 本地 JobRun ID
 * @param dagId 所属 DAG
 * @param dagName DAG 展示名称
 * @param dagsterJob 实际启动的 Dagster 作业名
 * @param dagsterRunId Dagster run ID；远端启动前可为空
 * @param triggerType MANUAL、CRON、BACKFILL 或 AUTO_RETRY
 * @param status 本地聚合运行状态
 * @param runMode NORMAL 或 DRY_RUN
 * @param timezone 计算 logical date 和数据区间使用的时区
 * @param logicalDate 调度周期的业务标识时刻
 * @param dataIntervalStart 数据区间左边界
 * @param dataIntervalEnd 数据区间右边界
 * @param backfillId 所属回填批次；普通运行为空
 * @param startedAt 开始时间
 * @param finishedAt 终态时间
 * @param triggeredBy 触发用户 ID；系统触发可为空
 * @param triggeredByName 面向界面的触发者名称
 * @param slaMissed 是否已超过 SLA 阈值
 * @param retrySourceRunId DAG 自动重跑来源；普通运行为空
 * @param runRetryAttempt DAG 自动重跑次数，首次运行为 0
 * @param pipelineVersionId 运行绑定的不可变流水线版本 ID；DEV 实时运行可为空
 * @param pipelineVersion 运行绑定的 DAG 内可读版本号；DEV 实时运行可为空
 */
public record JobRunDTO(
    UUID id,
    UUID dagId,
    String dagName,
    String dagsterJob,
    String dagsterRunId,
    String triggerType,
    String status,
    String runMode,
    String timezone,
    Instant logicalDate,
    Instant dataIntervalStart,
    Instant dataIntervalEnd,
    UUID backfillId,
    Instant startedAt,
    Instant finishedAt,
    UUID triggeredBy,
    String triggeredByName,
    Boolean slaMissed,
    UUID retrySourceRunId,
    Integer runRetryAttempt,
    UUID pipelineVersionId,
    Integer pipelineVersion
) {}
