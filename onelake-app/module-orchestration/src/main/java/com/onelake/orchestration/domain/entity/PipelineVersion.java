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
 * 流水线不可变发布版本（对应 {@code orchestration.pipeline_version}）。
 *
 * <p>每个版本保存 DAG、任务、边、参数和调度配置的完整 JSON 快照，供生产运行绑定和回溯。
 */
@Entity
@Table(name = "pipeline_version", schema = "orchestration")
@Getter
@Setter
public class PipelineVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private UUID dagId;

    /** DAG 内单调递增的发布版本号。 */
    @Column(nullable = false)
    private Integer version;

    /** DAG、任务、边、参数和调度配置的完整 JSON 快照。 */
    @Column(columnDefinition = "jsonb", nullable = false)
    @ColumnTransformer(write = "?::jsonb")
    private String snapshot;

    /** 规范化快照的内容哈希，用于去重和版本对比。 */
    @Column(length = 64)
    private String checksum;

    /** 版本状态：PUBLISHED 或 ARCHIVED。 */
    @Column(nullable = false, length = 16)
    private String status = "PUBLISHED";

    @Column(length = 512)
    private String note;

    private UUID publishedBy;

    @Column(length = 128)
    private String publishedByName;

    private Instant createdAt = Instant.now();
}
