package com.onelake.orchestration.dto;

public record OperatorVersionRequest(
    OperatorManifestDTO manifest,
    String changelog
) {
}
