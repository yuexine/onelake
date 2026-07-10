package com.onelake.orchestration.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 算子版本接口响应对象。
 *
 * @param id 版本记录 ID
 * @param version 语义版本号
 * @param manifest 不可变 Manifest 快照
 * @param changelog 版本说明
 * @param createdBy 发布用户
 * @param createdAt 发布时间
 */
public record OperatorVersionDTO(
    UUID id,
    String version,
    OperatorManifestDTO manifest,
    String changelog,
    UUID createdBy,
    Instant createdAt
) {
}
