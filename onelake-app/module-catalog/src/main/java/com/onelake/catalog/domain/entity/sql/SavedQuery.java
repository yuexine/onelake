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
@Table(name = "saved_query", schema = "catalog")
@Getter
@Setter
public class SavedQuery {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    private UUID ownerId;

    private String ownerName;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(nullable = false, columnDefinition = "text")
    private String sqlText;

    private boolean shared;

    private Instant createdAt = Instant.now();

    private Instant updatedAt = Instant.now();
}
