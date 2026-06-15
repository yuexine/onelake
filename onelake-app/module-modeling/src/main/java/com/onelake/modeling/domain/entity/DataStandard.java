package com.onelake.modeling.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "data_standard", schema = "modeling")
@Getter @Setter
public class DataStandard {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(nullable = false) private UUID tenantId;
    @Column(nullable = false) private String term;
    private String namingRule;
    @Column(columnDefinition = "jsonb") private String codeRule;
    private Instant createdAt = Instant.now();
}
