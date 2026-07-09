package com.onelake.orchestration.dto;

/**
 * 运行契约接口响应对象。
 *
 * <p>用于告诉前端某个编译目标是否已接入 Manifest、图级执行和 Dagster job。
 */
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
