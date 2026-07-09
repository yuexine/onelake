package com.onelake.orchestration.dto;

import java.util.Map;

/**
 * 创建或更新资源组的请求体。
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
