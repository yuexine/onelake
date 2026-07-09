package com.onelake.orchestration.dto;

/**
 * 创建或更新计算画像的请求体。
 */
public record ComputeProfileRequest(
    String code,
    String displayName,
    String engine,
    String status,
    Integer cpuCores,
    Integer memoryGb,
    Long maxScanBytes,
    Integer timeoutSeconds
) {
}
