package com.onelake.dataservice.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnTransformer;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "api_definition", schema = "dataservice")
@Getter @Setter
public class ApiDefinition {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(nullable = false) private UUID tenantId;
    @Column(nullable = false) private String apiPath;
    @Column(nullable = false) private String viewName;
    @Column(nullable = false, columnDefinition = "text") private String selectSql;
    private String sourceFqn;
    @Column(columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String requestParams;
    @Column(columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String responseSchema;
    private Integer qpsLimit = 20;
    @Column(nullable = false, length = 16) private String status = "DRAFT";   // DRAFT/PUBLISHED/DEPRECATED/OFFLINE
    private Integer currentVersion = 1;
    private Instant createdAt = Instant.now();
}
