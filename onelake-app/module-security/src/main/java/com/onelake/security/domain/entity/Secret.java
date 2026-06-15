package com.onelake.security.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "secret", schema = "security")
@Getter @Setter
public class Secret {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(nullable = false) private UUID tenantId;
    @Column(nullable = false, unique = true) private String refKey;
    @Column(nullable = false) private String kmsKeyId;
    @Column(nullable = false) private byte[] cipherText;
    private Instant rotatedAt;
    private Instant createdAt = Instant.now();
}
