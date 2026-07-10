package com.onelake.orchestration.dto;

import java.util.UUID;

/**
 * 新增流水线周期依赖的请求。
 *
 * @param upstreamDagId 上游流水线 ID
 * @param dependencyType SAME_CYCLE 或 CROSS_CYCLE
 * @param offsetGrain 跨周期粒度 HOUR、DAY 或 MONTH
 * @param offsetN 相对于下游 logical_date 的偏移量
 */
public record PipelineDependencyRequest(
        UUID upstreamDagId,
        String dependencyType,
        String offsetGrain,
        Integer offsetN
) {
}
