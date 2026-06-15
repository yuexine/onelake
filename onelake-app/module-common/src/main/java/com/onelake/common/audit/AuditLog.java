package com.onelake.common.audit;

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
 * 统一审计日志（对应《技术初始化文档》§7.1 common.audit_log）。
 * 只追加表，不更新/删除；主键 BIGINT GENERATED ALWAYS AS IDENTITY。
 */
@Entity
@Table(name = "audit_log", schema = "common")
@Getter
@Setter
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private UUID tenantId;

    private UUID actorId;

    @Column(nullable = false)
    private String action;            // CREATE / UPDATE / DELETE / PUBLISH ...

    @Column(nullable = false)
    private String resourceType;

    private String resourceId;

    @Column(columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String detail;

    private String traceId;

    private Instant occurredAt = Instant.now();
}
