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
 * Pipeline v2 task — first-class entity for one node in a pipeline DAG.
 *
 * <p>The pipeline mainline is Spark-only: tasks use {@code SPARK_SQL} /
 * {@code PYSPARK} plus structured dataflow config.
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

    /** Deprecated after the Spark-only cutover. Nullable for all new tasks. */
    private UUID modelId;

    /** SYNC_REF only. Nullable otherwise. */
    private UUID syncTaskId;

    /**
     * Engine/task-specific config. For Spark pipeline nodes, this holds SQL/script and
     * structured dataflow parameters.
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
