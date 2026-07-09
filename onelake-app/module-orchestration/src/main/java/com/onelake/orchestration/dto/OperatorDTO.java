package com.onelake.orchestration.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 算子市场接口响应对象。
 *
 * <p>同时承载算子基础信息、当前 Manifest、版本列表和当前租户安装状态。
 */
public record OperatorDTO(
    UUID id,
    String operatorRef,
    String category,
    String scope,
    String displayName,
    String description,
    String latestVersion,
    String status,
    boolean installed,
    String pinnedVersion,
    OperatorManifestDTO manifest,
    List<OperatorVersionDTO> versions,
    Instant createdAt
) {
}
