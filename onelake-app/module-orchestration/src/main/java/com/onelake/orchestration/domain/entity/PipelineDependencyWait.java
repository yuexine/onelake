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

/** 因上游依赖未就绪而等待后续 scheduler tick 重判的原始计划点。 */
@Entity
@Table(name = "pipeline_dependency_wait", schema = "orchestration")
@Getter
@Setter
public class PipelineDependencyWait {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private UUID dagId;

    @Column(nullable = false)
    private Instant logicalDate;

    @Column(nullable = false)
    private Instant scheduledAt;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();
}
