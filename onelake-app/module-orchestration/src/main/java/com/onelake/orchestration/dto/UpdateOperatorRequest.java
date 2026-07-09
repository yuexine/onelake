package com.onelake.orchestration.dto;

/**
 * 更新算子基础信息的请求体。
 */
public record UpdateOperatorRequest(
    String displayName,
    String description,
    String status
) {
}
