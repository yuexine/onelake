package com.onelake.security.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "role_binding", schema = "security")
@Getter @Setter
public class RoleBinding {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(nullable = false) private UUID tenantId;
    @Column(nullable = false) private UUID roleId;
    @Column(nullable = false, length = 32) private String resourceType;
    @Column(nullable = false) private String resourceRef;
    @Column(nullable = false, columnDefinition = "jsonb") private String actions;
}
