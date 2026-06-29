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
 * Notebook 文档元数据（内容 .ipynb 存 JupyterHub/MinIO）。
 */
@Entity
@Table(name = "notebook", schema = "analytics")
@Getter
@Setter
public class Notebook {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(nullable = false, length = 32)
    private String kernel = "python3";

    @Column(nullable = false, length = 512)
    private String storagePath;

    @Column(columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String paramsSchema;

    private UUID templateId;

    private UUID createdBy;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();
}
