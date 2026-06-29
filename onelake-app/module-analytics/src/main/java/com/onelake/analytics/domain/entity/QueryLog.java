package com.onelake.analytics.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * 查询日志（慢查询、配额、血缘命中证据；见 §5.4 可观测性）。
 * 通过 QueryLogRepository.saveAsync 异步写入，不影响主查询路径。
 */
@Entity
@Table(name = "query_log", schema = "analytics")
@Getter
@Setter
public class QueryLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private UUID datasetId;

    private UUID dashboardId;

    @Column(nullable = false, length = 32)
    private String sqlMd5;

    @Column(nullable = false)
    private Integer durationMs;

    @Column(nullable = false)
    private Integer rows;

    @Column(nullable = false)
    private Boolean cacheHit = false;

    private UUID createdBy;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();
}
