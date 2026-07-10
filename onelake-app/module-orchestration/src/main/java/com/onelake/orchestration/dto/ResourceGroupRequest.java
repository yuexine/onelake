package com.onelake.orchestration.dto;

import java.util.Map;

/**
 * 创建或更新资源组的请求体。
 *
 * @param code 租户内稳定编码
 * @param displayName 展示名称
 * @param engine 执行引擎
 * @param status ACTIVE 或 DISABLED
 * @param maxConcurrency 最大并发运行数
 * @param quotaCpu CPU 总配额
 * @param quotaMemoryGb 内存总配额，单位 GB
 * @param costPolicy 成本治理策略
 */
public record ResourceGroupRequest(
    String code,
    String displayName,
    String engine,
    String status,
    Integer maxConcurrency,
    Integer quotaCpu,
    Integer quotaMemoryGb,
    Map<String, Object> costPolicy
) {
}
