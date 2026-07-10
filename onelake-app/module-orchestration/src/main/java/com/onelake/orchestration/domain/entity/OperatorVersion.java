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
 * 不可变算子 Manifest 版本。
 *
 * <p>Operator 保存最新版本指针，本表保存每次发布的完整 Manifest 快照和变更说明。
 */
@Entity
@Table(name = "operator_version", schema = "orchestration")
@Getter
@Setter
public class OperatorVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** 所属算子主记录。 */
    @Column(nullable = false)
    private UUID operatorId;

    /** 不可变语义版本号。 */
    @Column(nullable = false, length = 24)
    private String version;

    /** 该版本完整 Manifest JSON 快照。 */
    @Column(columnDefinition = "jsonb", nullable = false)
    @ColumnTransformer(write = "?::jsonb")
    private String manifest;

    /** 版本变更说明。 */
    @Column(columnDefinition = "TEXT")
    private String changelog;

    /** 发布该版本的用户。 */
    private UUID createdBy;

    private Instant createdAt = Instant.now();
}
