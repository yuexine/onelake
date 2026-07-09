package com.onelake.orchestration.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 单个业务日期回填子运行响应对象。
 */
public record BackfillRunDTO(
        UUID id,
        UUID jobRunId,
        Instant logicalDate,
        Instant dataIntervalStart,
        Instant dataIntervalEnd,
        String status,
        String errorMsg
) {}
