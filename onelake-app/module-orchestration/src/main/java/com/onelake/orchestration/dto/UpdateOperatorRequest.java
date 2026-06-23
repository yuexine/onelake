package com.onelake.orchestration.dto;

public record UpdateOperatorRequest(
    String displayName,
    String description,
    String status
) {
}
