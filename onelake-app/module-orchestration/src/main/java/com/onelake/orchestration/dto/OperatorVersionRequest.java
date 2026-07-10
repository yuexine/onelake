package com.onelake.orchestration.dto;

/**
 * 发布算子新版本的请求体。
 *
 * @param manifest 新版本完整 Manifest
 * @param changelog 版本变更说明
 */
public record OperatorVersionRequest(
    OperatorManifestDTO manifest,
    String changelog
) {
}
