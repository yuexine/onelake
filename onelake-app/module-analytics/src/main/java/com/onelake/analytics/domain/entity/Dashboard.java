package com.onelake.analytics.domain.entity;

import com.onelake.analytics.domain.enums.DashboardStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * 大屏定义（自研编排器）。
 * spec 是草稿态组件数组（ScreenSpec）；发布时冻结为 dashboard_publication.snapshot。
 * version 是乐观锁；current_publication_id 指向当前线上 publication，加速读。
 */
@Entity
@Table(name = "dashboard", schema = "analytics")
@Getter
@Setter
public class Dashboard {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 512)
    private String description;

    @Column(columnDefinition = "jsonb", nullable = false)
    @ColumnTransformer(write = "?::jsonb")
    private String canvas;

    @Column(columnDefinition = "jsonb", nullable = false)
    @ColumnTransformer(write = "?::jsonb")
    private String spec = "[]";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DashboardStatus status = DashboardStatus.DRAFT;

    private UUID currentPublicationId;

    @Column(nullable = false)
    private Integer version = 0;

    @Column(length = 512)
    private String thumbnail;

    private UUID createdBy;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();
}
