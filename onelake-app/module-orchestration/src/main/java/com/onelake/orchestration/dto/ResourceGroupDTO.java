package com.onelake.orchestration.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 资源组接口响应对象。
 *
 * <p>资源组用于聚合运行引擎、并发配额、成本策略和可选计算画像。
 */
public record ResourceGroupDTO(
    UUID id,
    String code,
    String displayName,
    String engine,
    String status,
    boolean builtin,
    Integer maxConcurrency,
    Integer quotaCpu,
    Integer quotaMemoryGb,
    Map<String, Object> costPolicy,
    List<ComputeProfileDTO> computeProfiles,
    Instant createdAt,
    Instant updatedAt
) {
}
