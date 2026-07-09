package com.onelake.orchestration.domain.entity;

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
 * 编排资源组实体。
 *
 * <p>资源组定义运行引擎、并发和配额边界，供流水线与算子运行契约校验复用。
 */
@Entity
@Table(name = "resource_group", schema = "orchestration")
@Getter
@Setter
public class ResourceGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID tenantId;

    @Column(nullable = false, length = 64)
    private String code;

    @Column(nullable = false, length = 128)
    private String displayName;

    @Column(nullable = false, length = 32)
    private String engine;

    @Column(nullable = false, length = 16)
    private String status = "ACTIVE";

    private Integer maxConcurrency;
    private Integer quotaCpu;
    private Integer quotaMemoryGb;

    @Column(columnDefinition = "jsonb", nullable = false)
    @ColumnTransformer(write = "?::jsonb")
    private String costPolicy = "{}";

    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
}
