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

    /** 空表示平台级默认组，非空表示租户私有组。 */
    private UUID tenantId;

    /** 稳定资源组编码，流水线通过该值引用。 */
    @Column(nullable = false, length = 64)
    private String code;

    /** 控制台展示名称。 */
    @Column(nullable = false, length = 128)
    private String displayName;

    /** 资源组支持的执行引擎。 */
    @Column(nullable = false, length = 32)
    private String engine;

    /** ACTIVE 可用于运行契约，DISABLED 禁止新运行选择。 */
    @Column(nullable = false, length = 16)
    private String status = "ACTIVE";

    /** 资源组允许的并发运行上限。 */
    private Integer maxConcurrency;
    /** 资源组 CPU 总配额。 */
    private Integer quotaCpu;
    /** 资源组内存总配额 GB。 */
    private Integer quotaMemoryGb;

    /** 成本控制扩展策略 JSON，保持控制面可演进。 */
    @Column(columnDefinition = "jsonb", nullable = false)
    @ColumnTransformer(write = "?::jsonb")
    private String costPolicy = "{}";

    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
}
