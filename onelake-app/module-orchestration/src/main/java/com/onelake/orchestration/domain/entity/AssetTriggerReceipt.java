package com.onelake.orchestration.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** 一次资产来源在确定业务窗口内对下游 DAG 的持久化处理回执。 */
@Entity
@Table(name = "asset_trigger_receipt", schema = "orchestration")
@IdClass(AssetTriggerReceiptId.class)
@Getter
@Setter
public class AssetTriggerReceipt {

    @Column(nullable = false)
    private UUID tenantId;

    @Id
    @Column(nullable = false)
    private UUID dagId;

    @Id
    @Column(nullable = false, length = 64)
    private String triggerKey;

    private UUID eventId;

    @Column(nullable = false, length = 16)
    private String sourceType;

    @Column(nullable = false, length = 512)
    private String sourceRef;

    @Column(length = 128)
    private String batchId;

    private Instant logicalDate;

    private UUID pipelineVersionId;

    private UUID jobRunId;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();
}
