package com.onelake.orchestration.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;

/**
 * 创建业务日期区间回填请求。
 *
 * @param rangeStart 首个 logical_date，包含端点
 * @param rangeEnd 最后一个 logical_date，包含端点
 * @param grain HOUR、DAY 或 MONTH；为空时继承 DAG
 * @param maxParallel 批次内部最大并行子运行数
 */
public record CreateBackfillRequest(
        @NotNull Instant rangeStart,
        @NotNull Instant rangeEnd,
        String grain,
        @Positive Integer maxParallel
) {}
