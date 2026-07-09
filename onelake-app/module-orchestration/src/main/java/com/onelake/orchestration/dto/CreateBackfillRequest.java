package com.onelake.orchestration.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;

/**
 * 创建业务日期区间回填请求。
 */
public record CreateBackfillRequest(
        @NotNull Instant rangeStart,
        @NotNull Instant rangeEnd,
        String grain,
        @Positive Integer maxParallel
) {}
