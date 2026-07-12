package com.onelake.orchestration.domain.entity;

import com.onelake.orchestration.domain.enums.TaskCompileStatus;
import com.onelake.orchestration.domain.enums.TaskCategory;
import com.onelake.orchestration.domain.enums.TaskType;
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
 * 流水线 V2 节点实体：流水线 DAG 中的一个一等节点。
 *
 * <p>当前流水线主路径以 Spark 为执行底座：节点使用 {@code SPARK_SQL} /
 * {@code PYSPARK}，并通过结构化 {@code config} 描述数据流参数。
 */
@Entity
@Table(name = "pipeline_task", schema = "orchestration")
@Getter
@Setter
public class PipelineTask {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** 节点所属租户，用于回调和运行记录的租户校验。 */
    @Column(nullable = false)
    private UUID tenantId;

    /** 节点所属流水线。 */
    @Column(nullable = false)
    private UUID dagId;

    /** 流水线内稳定唯一键，同时必须等于 Dagster step key。 */
    @Column(nullable = false, length = 128)
    private String taskKey;

    /** 节点行为类型，决定端口、配置校验和执行渲染方式。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TaskType taskType;

    /** 节点执行语义分类；由 taskType 的服务端映射决定。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TaskCategory category = TaskCategory.EXEC;

    /** 从算子市场生成节点时使用的稳定算子引用。 */
    @Column(length = 128)
    private String operatorRef;

    /** 发布或运行时锁定的算子版本。 */
    @Column(length = 32)
    private String operatorVersion;

    /** 画布展示名称。 */
    @Column(nullable = false, length = 256)
    private String name;

    /** 运行引擎标识；当前执行路径为 SPARK_SQL/PYSPARK。 */
    @Column(nullable = false, length = 32)
    private String engine = "SPARK_SQL";

    /** 节点产出的目标资产全限定名。 */
    @Column(length = 256)
    private String targetFqn;

    /** 目标时间分区字段名。 */
    @Column(length = 64)
    private String partitionKey;

    /** 节点分区粒度；为空时继承 DAG partitionGrain。 */
    @Column(length = 16)
    private String partitionGrain;

    /** Spark-only 切换后的历史兼容字段；新节点允许为空。 */
    private UUID modelId;

    /** 仅 {@code SYNC_REF} 节点使用，其他节点允许为空。 */
    private UUID syncTaskId;

    /**
     * 引擎或节点类型专属配置。Spark 流水线节点在这里保存 SQL/脚本和结构化数据流参数。
     */
    @Column(columnDefinition = "jsonb", nullable = false)
    @ColumnTransformer(write = "?::jsonb")
    private String config = "{}";

    /** 最近一次编译状态。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TaskCompileStatus compileStatus = TaskCompileStatus.DRAFT;

    /** 最近一次编译错误摘要，供编辑器直接展示。 */
    @Column(length = 4000)
    private String compileError;

    /** 是否具备真实执行能力；控制/观测节点可以为 false。 */
    @Column(nullable = false)
    private Boolean executable = false;

    /** 画布横坐标。 */
    @Column(name = "position_x")
    private Integer positionX;

    /** 画布纵坐标。 */
    @Column(name = "position_y")
    private Integer positionY;

    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
}
