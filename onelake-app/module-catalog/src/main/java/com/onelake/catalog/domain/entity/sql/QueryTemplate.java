package com.onelake.catalog.domain.entity.sql;

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

@Entity
@Table(name = "query_template", schema = "catalog")
@Getter
@Setter
public class QueryTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    private UUID ownerId;

    private String ownerName;

    @Column(nullable = false, length = 128)
    private String name;

    private String category;

    @Column(columnDefinition = "text")
    private String description;

    @Column(nullable = false, columnDefinition = "text")
    private String sqlTemplate;

    /**
     * JSONB 数组，每项形如：
     * {"name":"dt","type":"date","required":true,"default":"2026-01-01","description":"业务日期"}
     * type 取值：string | number | date | timestamp | identifier | boolean
     */
    @Column(nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String placeholders = "[]";

    private boolean shared;

    private Instant createdAt = Instant.now();

    private Instant updatedAt = Instant.now();
}
