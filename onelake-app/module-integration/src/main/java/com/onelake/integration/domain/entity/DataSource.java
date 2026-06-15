package com.onelake.integration.domain.entity;

import com.onelake.integration.domain.enums.DataSourceType;
import com.onelake.integration.domain.enums.EnvLevel;
import com.onelake.integration.domain.enums.Health;
import com.onelake.integration.domain.enums.NetworkMode;
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
 * 数据源实体（对应《技术初始化文档》§6.10 与 §7.2 integration.datasource）。
 * 密码绝不明文落库，secret_ref 指向 security.secret。
 */
@Entity
@Table(name = "datasource", schema = "integration")
@Getter
@Setter
public class DataSource {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    private UUID projectId;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private DataSourceType type;

    @Column(columnDefinition = "jsonb", nullable = false)
    @ColumnTransformer(write = "?::jsonb")
    private String config;           // host/port/db 等非敏感配置

    private String secretRef;        // 指向 security.secret 的密文引用

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private NetworkMode networkMode = NetworkMode.DIRECT;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private EnvLevel envLevel = EnvLevel.PROD;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private Health health = Health.UNKNOWN;

    private Instant lastCheckAt;
    private Instant createdAt = Instant.now();
}
