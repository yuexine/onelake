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

    private Instant createdAt = Instant.now();
}
