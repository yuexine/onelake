package com.onelake.orchestration.domain.entity;

import com.onelake.orchestration.domain.enums.TaskCompileStatus;
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

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private UUID dagId;

    @Column(nullable = false, length = 128)
    private String taskKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TaskType taskType;

    @Column(nullable = false, length = 256)
    private String name;

    @Column(nullable = false, length = 32)
    private String engine = "SPARK_SQL";

    @Column(length = 256)
    private String targetFqn;

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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TaskCompileStatus compileStatus = TaskCompileStatus.DRAFT;

    @Column(length = 4000)
    private String compileError;

    @Column(nullable = false)
    private Boolean executable = false;

    @Column(name = "position_x")
    private Integer positionX;

    @Column(name = "position_y")
    private Integer positionY;

    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
}
