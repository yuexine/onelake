package com.onelake.orchestration.dto;

import java.util.Map;

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
