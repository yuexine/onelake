package com.onelake.orchestration.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 计算画像接口响应对象。
 *
 * <p>计算画像挂在资源组下，用于描述 Spark 运行的 CPU、内存、扫描量和超时约束。
 *
 * @param id 计算画像 ID
 * @param code 资源组内稳定编码
 * @param displayName 展示名称
 * @param engine 适用执行引擎
 * @param status ACTIVE 或 DISABLED
 * @param cpuCores CPU 核数
 * @param memoryGb 内存上限，单位 GB
 * @param maxScanBytes 单次运行最大扫描字节数
 * @param timeoutSeconds 单次运行超时秒数
 * @param createdAt 创建时间
 * @param updatedAt 最后更新时间
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
