package com.onelake.orchestration.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * DAG 定义（对应《技术初始化文档》§7.3 orchestration.dag）。
 */
@Entity
@Table(name = "dag", schema = "orchestration")
@Getter
@Setter
public class Dag {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String dagsterJob;

    @Column(columnDefinition = "jsonb", nullable = false)
    @ColumnTransformer(write = "?::jsonb")
    private String definition;     // 节点/依赖（前端 X6 画布）

    private String scheduleCron;

    private Boolean enabled = true;

    private Integer version = 1;

    /** 流水线 V2 字段，用于承载画布类型、发布状态和 Spark 执行资源。 */
    private String pipelineKind;     // BLANK | ODS_DWD | MULTI_LAYER
    private String status;           // DRAFT | VALIDATED | PUBLISHED
    private String engine;           // 当前流水线主路径使用 SPARK。
    private String resourceGroup;    // 例如 spark-default。
    private String computeProfile;   // 例如 spark-small。
    private String partitionGrain = "DAY";

    /** M2 调度策略；默认值与 V16 迁移保持一致，保证存量流水线行为不变。 */
    @Column(nullable = false, length = 64)
    private String timezone = "Asia/Shanghai";

    @Column(nullable = false)
    private Boolean catchup = false;

    @Column(nullable = false)
    private Integer maxActiveRuns = 1;

    @Column(nullable = false)
    private Integer priority = 5;

    private Integer slaMinutes;
    private Integer timeoutMinutes;

    @Column(nullable = false, length = 16)
    private String scheduleMode = "NORMAL";

    @Column(nullable = false, length = 16)
    private String misfirePolicy = "FIRE_ONCE";

    private UUID calendarId;
    private Instant scheduleStart;
    private Instant scheduleEnd;

    private Instant createdAt = Instant.now();
}
