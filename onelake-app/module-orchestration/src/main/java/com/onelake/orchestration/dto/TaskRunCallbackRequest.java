package com.onelake.orchestration.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.onelake.orchestration.domain.enums.TaskRunStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Dagster 节点执行器回调的请求体。
 *
 * <p>该对象只承载节点状态、运行指标和日志/产物位置；租户身份由 runId 反查得到。
 *
 * @param status 节点目标状态
 * @param startedAt 节点开始时间
 * @param finishedAt 节点终态时间
 * @param errorMsg 错误摘要
 * @param artifactPath 输出产物位置
 * @param rowsWritten 写入行数
 * @param scanBytes 扫描字节数
 * @param logRef 租户/run 前缀下的对象存储日志键
 * @param attempt 跨 Dagster run 累计 attempt
 * @param dagsterStepKey Dagster step key，GRAPH 模式应等于 taskKey
 * @param outputs 节点成功输出，包含 rowsWritten、artifactPath 和自定义 KV
 */
public record TaskRunCallbackRequest(
        @NotNull TaskRunStatus status,
        Instant startedAt,
        Instant finishedAt,
        @Size(max = 4000) String errorMsg,
        @Size(max = 512) String artifactPath,
        @PositiveOrZero Long rowsWritten,
        @PositiveOrZero Long scanBytes,
        @Size(max = 512) String logRef,
        @Min(1) Integer attempt,
        @Size(max = 128) String dagsterStepKey,
        JsonNode outputs
) {
}
