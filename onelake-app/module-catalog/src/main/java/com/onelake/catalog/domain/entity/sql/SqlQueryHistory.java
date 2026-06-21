package com.onelake.catalog.domain.entity.sql;

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

@Entity
@Table(name = "sql_query_history", schema = "catalog")
@Getter
@Setter
public class SqlQueryHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    private UUID userId;

    private String runner;

    @Column(nullable = false, columnDefinition = "text")
    private String sqlText;

    @Column(nullable = false, length = 32)
    private String engine = "TRINO";

    private String resourceGroup;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(length = 128)
    private String trinoQueryId;

    private Long durationMs;

    private Long scanBytes;

    private Long rowCount;

    private String errorCode;

    @Column(columnDefinition = "text")
    private String errorMessage;

    private Instant createdAt = Instant.now();
}
