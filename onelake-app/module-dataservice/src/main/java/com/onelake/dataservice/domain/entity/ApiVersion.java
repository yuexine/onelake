package com.onelake.dataservice.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "api_version", schema = "dataservice")
@Getter @Setter
public class ApiVersion {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(nullable = false) private UUID apiId;
    @Column(nullable = false) private Integer version;
    @Column(nullable = false, columnDefinition = "jsonb") private String spec;
    private Instant publishedAt;
    private Instant deprecatedAt;
}
