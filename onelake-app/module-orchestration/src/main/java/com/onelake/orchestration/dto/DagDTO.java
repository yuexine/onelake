package com.onelake.orchestration.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * DAG 接口响应对象。
 *
 * <p>用于编排画布、运行触发和运行历史页面展示 DAG 定义、可触发状态与最近一次运行。
 */
public record DagDTO(
    UUID id,
    String name,
    String dagsterJob,
    Map<String, Object> definition,
    String scheduleCron,
    Boolean enabled,
    Boolean triggerable,
    String triggerBlockedReason,
    Integer version,
    Instant createdAt,
    JobRunDTO lastRun
) {}
