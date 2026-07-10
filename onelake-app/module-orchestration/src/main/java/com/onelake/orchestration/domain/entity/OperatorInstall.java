package com.onelake.orchestration.domain.entity;

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
 * 租户算子安装记录。
 *
 * <p>平台级算子通过安装关系进入租户可用集合；固定版本允许租户在平台发布新版本后
 * 保持运行契约不变。
 */
@Entity
@Table(name = "operator_install", schema = "orchestration")
@Getter
@Setter
public class OperatorInstall {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** 安装目标租户。 */
    @Column(nullable = false)
    private UUID tenantId;

    /** 被安装的算子主记录。 */
    @Column(nullable = false)
    private UUID operatorId;

    /** 可选固定版本；为空时跟随算子 latestVersion。 */
    @Column(length = 24)
    private String pinnedVersion;

    private Instant installedAt = Instant.now();
}
