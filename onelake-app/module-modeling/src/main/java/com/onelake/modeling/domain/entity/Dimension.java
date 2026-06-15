package com.onelake.modeling.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "dimension", schema = "modeling")
@Getter @Setter
public class Dimension {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(nullable = false) private UUID tenantId;
    private UUID domainId;
    @Column(nullable = false) private String code;
    @Column(nullable = false) private String name;
    @Column(nullable = false, length = 16) private String dimType;
    @Column(columnDefinition = "jsonb") private String attributes;
}
