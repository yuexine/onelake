package com.onelake.orchestration.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 算子市场接口响应对象。
 *
 * <p>同时承载算子基础信息、当前 Manifest、版本列表和当前租户安装状态。
 *
 * @param id 算子 ID
 * @param operatorRef 跨版本稳定引用
 * @param category 算子类别
 * @param scope BUILTIN、CUSTOM 或 TENANT_PRIVATE
 * @param displayName 展示名称
 * @param description 功能描述
 * @param latestVersion 最新发布版本
 * @param status ACTIVE 或 DEPRECATED
 * @param installed 当前租户是否已安装
 * @param pinnedVersion 当前租户锁定的版本
 * @param manifest 当前有效 Manifest
 * @param versions 可用版本列表
 * @param createdAt 创建时间
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
