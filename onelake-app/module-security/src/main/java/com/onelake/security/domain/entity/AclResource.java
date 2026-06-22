package com.onelake.security.domain.entity;

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

/**
 * SavedQuery / QueryTemplate 等共享资源的 ACL 条目（Sprint 5b）。
 *
 * resource_type 取值：SAVED_QUERY | QUERY_TEMPLATE
 * grantee_type  取值：USER | ROLE | GROUP
 * permission    取值：VIEW | RUN | EDIT
 *
 * owner 权限不走这张表（业务代码按 ownerId 直查判定）。
 */
@Entity
@Table(name = "acl_resource", schema = "security")
@Getter
@Setter
public class AclResource {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 32)
    private String resourceType;

    @Column(nullable = false)
    private UUID resourceId;

    @Column(nullable = false, length = 16)
    private String granteeType;

    @Column(nullable = false)
    private UUID granteeId;

    @Column(nullable = false, length = 16)
    private String permission;

    private UUID grantedBy;

    private String grantedByName;

    private Instant createdAt = Instant.now();
}
