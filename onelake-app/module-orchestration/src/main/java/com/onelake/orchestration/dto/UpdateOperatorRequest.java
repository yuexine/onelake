package com.onelake.orchestration.dto;

/**
 * 更新算子基础信息的请求体。
 *
 * @param displayName 新展示名称；为空表示不修改
 * @param description 新描述；为空表示不修改
 * @param status ACTIVE 或 DEPRECATED；为空表示不修改
 */
public record UpdateOperatorRequest(
    String displayName,
    String description,
    String status
) {
}
