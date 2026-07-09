package com.onelake.orchestration.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

/**
 * 单个业务日期回填子运行响应对象。
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
