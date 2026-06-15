package com.onelake.dataservice.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "app_key", schema = "dataservice")
@Getter @Setter
public class AppKey {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(nullable = false) private UUID tenantId;
    @Column(nullable = false, unique = true) private String appKey;
    @Column(nullable = false) private String secretHash;
    private UUID ownerId;
    @Column(columnDefinition = "jsonb") private String ipWhitelist;
    private Long quotaDaily;
    private Instant expiresAt;
    @Column(nullable = false, length = 16) private String status = "ACTIVE";
    private Instant createdAt = Instant.now();
}
