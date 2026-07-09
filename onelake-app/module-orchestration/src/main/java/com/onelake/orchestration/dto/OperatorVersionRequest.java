package com.onelake.orchestration.dto;

/**
 * 发布算子新版本的请求体。
 */
public record OperatorVersionRequest(
    OperatorManifestDTO manifest,
    String changelog
) {
}
