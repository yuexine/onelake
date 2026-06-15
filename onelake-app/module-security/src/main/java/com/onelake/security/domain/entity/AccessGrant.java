package com.onelake.security.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "access_grant", schema = "security")
@Getter @Setter
public class AccessGrant {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(nullable = false) private UUID tenantId;
    @Column(nullable = false) private UUID subjectId;
    @Column(nullable = false) private String assetFqn;
    @Column(columnDefinition = "jsonb") private String columns;
    @Column(nullable = false, columnDefinition = "jsonb") private String permissions;   // {query,download,api}
    @Column(nullable = false, length = 16) private String status = "ACTIVE";   // ACTIVE/EXPIRED/REVOKED
    private Instant grantedAt = Instant.now();
    private Instant expiresAt;
}
