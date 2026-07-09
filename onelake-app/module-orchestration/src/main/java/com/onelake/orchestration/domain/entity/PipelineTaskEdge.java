package com.onelake.orchestration.domain.entity;

import com.onelake.orchestration.domain.enums.EdgeLayer;
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

import java.time.Instant;
import java.util.UUID;

/**
 * 流水线节点之间的有向边。
 *
 * <p>用于保存 Spark 流水线内部的数据流连线，以及端口、资产和触发策略等契约。
 */
@Entity
@Table(name = "pipeline_task_edge", schema = "orchestration")
@Getter
@Setter
public class PipelineTaskEdge {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private UUID dagId;

    @Column(nullable = false, length = 128)
    private String sourceKey;

    @Column(nullable = false, length = 128)
    private String targetKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private EdgeLayer edgeLayer = EdgeLayer.PIPELINE;

    @Column(length = 32)
    private String sourcePort = "out";

    @Column(length = 32)
    private String targetPort = "in";

    @Column(name = "source_output", length = 64)
    private String sourceOutput = "out";

    @Column(name = "target_input", length = 64)
    private String targetInput = "in";

    @Column(name = "asset_fqn", length = 256)
    private String assetFqn;

    @Column(name = "input_alias", length = 64)
    private String inputAlias;

    @Column(name = "join_role", length = 32)
    private String joinRole;

    @Column(name = "trigger_policy", length = 32)
    private String triggerPolicy = "ALL_SUCCEEDED";

    @Column(name = "freshness_policy", length = 32)
    private String freshnessPolicy = "LATEST";

    @Column(nullable = false)
    private Boolean auto = false;

    private Instant createdAt = Instant.now();
}
