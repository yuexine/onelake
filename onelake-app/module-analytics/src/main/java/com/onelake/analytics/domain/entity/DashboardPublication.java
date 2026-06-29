package com.onelake.analytics.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnTransformer;

import java.time.Instant;
import java.util.UUID;

/**
 * 大屏发布快照（每次发布插一行，带版本）。
 * is_current = true 表示当前线上版（每个 dashboard 同时仅一条）。
 * share_token 仅在 is_public = true 时非空，用于 /share/screen/:token 公开通道。
 */
@Entity
@Table(name = "dashboard_publication", schema = "analytics")
@Getter
@Setter
public class DashboardPublication {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID dashboardId;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private Integer version = 1;

    @Column(columnDefinition = "jsonb", nullable = false)
    @ColumnTransformer(write = "?::jsonb")
    private String snapshot;

    @Column(length = 64, unique = true)
    private String shareToken;

    @Column(nullable = false)
    private Boolean isPublic = false;

    @Column(nullable = false)
    private Boolean isCurrent = true;

    private Instant expireAt;

    private UUID publishedBy;

    @Column(nullable = false)
    private Instant publishedAt = Instant.now();
}
