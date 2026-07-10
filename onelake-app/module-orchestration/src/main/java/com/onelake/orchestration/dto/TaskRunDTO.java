package com.onelake.orchestration.dto;

import com.onelake.orchestration.domain.entity.TaskRun;
import com.onelake.orchestration.domain.enums.TaskRunStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * 节点运行实例接口响应对象。
 *
 * <p>包含累计 attempt、持久化日志引用、运行指标、错误和产物位置。
 *
 * @param id TaskRun ID
 * @param jobRunId 所属 JobRun
 * @param taskKey 流水线内稳定节点键
 * @param status 节点状态
 * @param attempt 跨 Dagster run 累计 attempt
 * @param logRef 对象存储日志键
 * @param dagsterStepKey Dagster step key
 * @param rowsWritten 写入行数
 * @param scanBytes 扫描字节数
 * @param errorMsg 错误摘要
 * @param artifactPath 输出产物位置
 * @param startedAt 节点开始时间
 * @param finishedAt 节点终态时间
 */
public record TaskRunDTO(
        UUID id,
        UUID jobRunId,
        String taskKey,
        TaskRunStatus status,
        Integer attempt,
        String logRef,
        String dagsterStepKey,
        Long rowsWritten,
        Long scanBytes,
        String errorMsg,
        String artifactPath,
        Instant startedAt,
        Instant finishedAt
) {
    /** 从 TaskRun 实体创建 API 投影，不暴露租户和内部更新时间字段。 */
    public static TaskRunDTO of(TaskRun t) {
        return new TaskRunDTO(
                t.getId(), t.getJobRunId(), t.getTaskKey(), t.getStatus(),
                t.getAttempt(), t.getLogRef(), t.getDagsterStepKey(),
                t.getRowsWritten(), t.getScanBytes(), t.getErrorMsg(), t.getArtifactPath(),
                t.getStartedAt(), t.getFinishedAt()
        );
    }
}
