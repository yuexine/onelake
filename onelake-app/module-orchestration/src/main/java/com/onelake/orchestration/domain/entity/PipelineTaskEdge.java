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

    /** 边所属租户。 */
    @Column(nullable = false)
    private UUID tenantId;

    /** 边所属流水线。 */
    @Column(nullable = false)
    private UUID dagId;

    /** 上游节点稳定 taskKey。 */
    @Column(nullable = false, length = 128)
    private String sourceKey;

    /** 下游节点稳定 taskKey。 */
    @Column(nullable = false, length = 128)
    private String targetKey;

    /** PIPELINE 数据流边或 TASK 节点内部边等层级。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private EdgeLayer edgeLayer = EdgeLayer.PIPELINE;

    /** 画布源端口 ID。 */
    @Column(length = 32)
    private String sourcePort = "out";

    /** 画布目标端口 ID。 */
    @Column(length = 32)
    private String targetPort = "in";

    /** 上游输出契约名。 */
    @Column(name = "source_output", length = 64)
    private String sourceOutput = "out";

    /** 下游输入契约名。 */
    @Column(name = "target_input", length = 64)
    private String targetInput = "in";

    /** 边上传递的资产全限定名。 */
    @Column(name = "asset_fqn", length = 256)
    private String assetFqn;

    /** 下游 SQL/脚本引用该输入时使用的别名。 */
    @Column(name = "input_alias", length = 64)
    private String inputAlias;

    /** 多输入节点中的连接角色，例如 LEFT/RIGHT。 */
    @Column(name = "join_role", length = 32)
    private String joinRole;

    /** 上游满足策略，默认要求全部成功。 */
    @Column(name = "trigger_policy", length = 32)
    private String triggerPolicy = "ALL_SUCCEEDED";

    /** 资产新鲜度选择策略。 */
    @Column(name = "freshness_policy", length = 32)
    private String freshnessPolicy = "LATEST";

    /** 是否由模板或编译器自动生成。 */
    @Column(nullable = false)
    private Boolean auto = false;

    private Instant createdAt = Instant.now();
}
