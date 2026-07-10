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
 * 计算画像实体。
 *
 * <p>计算画像挂在资源组下，用于表达一次 Spark 运行可使用的资源规格。
 */
@Entity
@Table(name = "compute_profile", schema = "orchestration")
@Getter
@Setter
public class ComputeProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** 所属资源组 ID。 */
    @Column(nullable = false)
    private UUID resourceGroupId;

    /** 组内唯一规格编码，例如 spark-small。 */
    @Column(nullable = false, length = 64)
    private String code;

    /** 控制台展示名称。 */
    @Column(nullable = false, length = 128)
    private String displayName;

    /** 可使用该规格的执行引擎。 */
    @Column(nullable = false, length = 32)
    private String engine;

    /** ACTIVE 可选、DISABLED 停用。 */
    @Column(nullable = false, length = 16)
    private String status = "ACTIVE";

    /** 单次运行 CPU 核数。 */
    private Integer cpuCores;
    /** 单次运行内存 GB。 */
    private Integer memoryGb;
    /** 单次运行允许扫描的最大字节数。 */
    private Long maxScanBytes;
    /** 单次运行超时秒数。 */
    private Integer timeoutSeconds;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
}
