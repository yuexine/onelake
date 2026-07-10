package com.onelake.orchestration.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * DAG 接口响应对象。
 *
 * <p>用于编排画布、运行触发和运行历史页面展示 DAG 定义、可触发状态与最近一次运行。
 *
 * @param id DAG ID
 * @param name 展示名称
 * @param dagsterJob 当前绑定的 Dagster 作业名
 * @param definition 结构化 DAG 定义
 * @param scheduleCron 可选调度表达式
 * @param enabled 是否允许调度
 * @param triggerable 当前运行时契约是否允许手动触发
 * @param triggerBlockedReason 不可触发原因
 * @param version 定义版本号
 * @param createdAt 创建时间
 * @param lastRun 最近一次运行摘要
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
