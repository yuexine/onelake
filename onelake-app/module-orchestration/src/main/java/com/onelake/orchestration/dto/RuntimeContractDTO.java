package com.onelake.orchestration.dto;

/**
 * 运行契约接口响应对象。
 *
 * <p>用于告诉前端某个编译目标是否已接入 Manifest、图级执行和 Dagster job。
 *
 * @param compileTarget Manifest 声明的编译目标
 * @param engine 映射后的执行引擎
 * @param dagsterJob 需要存在的 Dagster 作业
 * @param manifestSupported 是否支持该 Manifest 目标
 * @param graphExecutionSupported 是否支持图级执行
 * @param dagsterJobAvailable 运行时是否发现目标作业
 * @param status READY、RESTRICTED 或 MISSING_DAGSTER_JOB
 * @param blockedReason 阻断原因
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
