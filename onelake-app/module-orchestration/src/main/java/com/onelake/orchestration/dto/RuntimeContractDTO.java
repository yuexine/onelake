package com.onelake.orchestration.dto;

public record RuntimeContractDTO(
    String compileTarget,
    String engine,
    String dagsterJob,
    boolean manifestSupported,
    boolean graphExecutionSupported,
    boolean dagsterJobAvailable,
    String status,
    String blockedReason
) {
}
