package com.onelake.analytics.domain.entity;

import com.onelake.analytics.domain.enums.TemplateCategory;
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
 * 算法模板（平台预置 + 租户自定义；P4d 启用）。
 * tenant_id = NULL 表示平台预置（所有租户可见）。
 */
@Entity
@Table(name = "notebook_template", schema = "analytics")
@Getter
@Setter
public class NotebookTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID tenantId;

    @Column(nullable = false, length = 128)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 64)
    private TemplateCategory category;

    @Column(length = 512)
    private String description;

    @Column(nullable = false, length = 512)
    private String storagePath;

    @Column(columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String paramsSchema;

    @Column(nullable = false, length = 32)
    private String kernel = "python3";

    @Column(length = 64)
    private String icon;

    @Column(nullable = false)
    private Integer sortOrder = 0;

    private UUID createdBy;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();
}
