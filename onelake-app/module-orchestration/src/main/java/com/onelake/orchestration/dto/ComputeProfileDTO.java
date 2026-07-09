package com.onelake.orchestration.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 计算画像接口响应对象。
 *
 * <p>计算画像挂在资源组下，用于描述 Spark 运行的 CPU、内存、扫描量和超时约束。
 */
public record ComputeProfileDTO(
    UUID id,
    String code,
    String displayName,
    String engine,
    String status,
    Integer cpuCores,
    Integer memoryGb,
    Long maxScanBytes,
    Integer timeoutSeconds,
    Instant createdAt,
    Instant updatedAt
) {
}
