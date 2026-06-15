package com.onelake.modeling.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "metric", schema = "modeling")
@Getter @Setter
public class Metric {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(nullable = false) private UUID tenantId;
    private UUID domainId;
    @Column(nullable = false) private String code;
    @Column(nullable = false) private String name;
    @Column(nullable = false, length = 16) private String metricType;
    @Column(columnDefinition = "text") private String caliberSql;
    private String dbtModel;
    private Integer version = 1;
}
