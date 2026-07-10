package com.onelake.orchestration.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

/**
 * 单个业务周期回填子运行响应对象。
 *
 * @param id backfill_run ID
 * @param jobRunId 已派发的真实 JobRun；等待派发时为空
 * @param logicalDate 业务周期标识
 * @param dataIntervalStart 数据区间左边界
 * @param dataIntervalEnd 数据区间右边界
 * @param status 队列明细状态
 * @param errorMsg 派发或执行错误摘要
 */
public record BackfillRunDTO(
        UUID id,
        @JsonProperty("job_run_id")
        UUID jobRunId,
        @JsonProperty("logical_date")
        Instant logicalDate,
        @JsonProperty("data_interval_start")
        Instant dataIntervalStart,
        @JsonProperty("data_interval_end")
        Instant dataIntervalEnd,
        String status,
        @JsonProperty("error_msg")
        String errorMsg
) {}
