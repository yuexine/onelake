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

/** 流水线之间按业务周期判定的调度依赖。 */
@Entity
@Table(name = "pipeline_dependency", schema = "orchestration")
@Getter
@Setter
public class PipelineDependency {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private UUID downstreamDagId;

    @Column(nullable = false)
    private UUID upstreamDagId;

    /** SAME_CYCLE 或 CROSS_CYCLE。 */
    @Column(nullable = false, length = 16)
    private String dependencyType = "SAME_CYCLE";

    /** CROSS_CYCLE 使用 HOUR、DAY 或 MONTH。 */
    @Column(length = 16)
    private String offsetGrain;

    /** 相对于下游 logical_date 的周期偏移，例如 -1 表示前一周期。 */
    @Column(name = "offset_n", nullable = false)
    private Integer offsetN = 0;

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();
}
