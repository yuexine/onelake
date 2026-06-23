package com.onelake.orchestration.dto;

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
