package com.onelake.integration.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 数据源对外 DTO。
 */
public record DataSourceDTO(
    UUID id,
    UUID tenantId,
    String name,
    String type,
    String host,
    Integer port,
    String dbName,
    String username,
    String health,
    Long rttMs,
    UUID projectId,
    String networkMode,
    String envLevel,
    String secretRef,
    Instant lastCheckAt,
    Instant createdAt
) {}
