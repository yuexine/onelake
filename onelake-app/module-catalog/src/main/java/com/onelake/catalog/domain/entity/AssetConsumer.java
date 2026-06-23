package com.onelake.catalog.domain.entity;

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
 * 资产消费者投影（对应《血缘图模块完善设计方案》§5.1.1 catalog.asset_consumer）。
 *
 * <p>由 module-dataservice / module-orchestration 通过 Outbox 事件发布后，
 * 由 {@code AssetConsumerEventHandler} 写入。catalog 模块只查不直读其它 schema。
 */
@Entity
@Table(name = "asset_consumer", schema = "catalog")
@Getter
@Setter
public class AssetConsumer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String assetFqn;

    @Column(nullable = false, length = 16)
    private String consumerType;

    @Column(nullable = false, length = 256)
    private String consumerRef;

    @Column(length = 256)
    private String consumerName;

    @Column(length = 128)
    private String ownerName;

    @Column(nullable = false, length = 16)
    private String status = "ACTIVE";

    private Instant syncedAt = Instant.now();
}
