package com.onelake.orchestration.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 资源组接口响应对象。
 *
 * <p>资源组用于聚合运行引擎、并发配额、成本策略和可选计算画像。
 *
 * @param id 资源组 ID
 * @param code 租户内稳定编码
 * @param displayName 展示名称
 * @param engine 执行引擎
 * @param status ACTIVE 或 DISABLED
 * @param builtin 是否为平台内置资源组
 * @param maxConcurrency 最大并发运行数
 * @param quotaCpu CPU 总配额
 * @param quotaMemoryGb 内存总配额，单位 GB
 * @param costPolicy 成本治理策略
 * @param computeProfiles 可选择的计算画像
 * @param createdAt 创建时间
 * @param updatedAt 最后更新时间
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
