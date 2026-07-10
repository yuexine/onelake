package com.onelake.orchestration.dto;

/**
 * 创建或更新计算画像的请求体。
 *
 * @param code 资源组内稳定编码
 * @param displayName 展示名称
 * @param engine 执行引擎
 * @param status ACTIVE 或 DISABLED
 * @param cpuCores CPU 核数
 * @param memoryGb 内存上限，单位 GB
 * @param maxScanBytes 单次运行最大扫描字节数
 * @param timeoutSeconds 单次运行超时秒数
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
