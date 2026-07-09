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
 * 租户算子安装记录实体。
 */
@Entity
@Table(name = "operator_install", schema = "orchestration")
@Getter
@Setter
public class OperatorInstall {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private UUID operatorId;

    @Column(length = 24)
    private String pinnedVersion;

    private Instant installedAt = Instant.now();
}
