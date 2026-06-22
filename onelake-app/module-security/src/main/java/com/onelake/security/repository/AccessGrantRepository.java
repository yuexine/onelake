package com.onelake.security.repository;

import com.onelake.security.domain.entity.AccessGrant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccessGrantRepository extends JpaRepository<AccessGrant, UUID> {
    List<AccessGrant> findBySubjectIdAndStatus(UUID subjectId, String status);
    List<AccessGrant> findBySubjectId(UUID subjectId);
    List<AccessGrant> findByTenantIdAndSubjectIdAndStatus(UUID tenantId, UUID subjectId, String status);
    List<AccessGrant> findByTenantIdAndStatus(UUID tenantId, String status);
    List<AccessGrant> findByTenantIdAndStatusInOrderByGrantedAtDesc(UUID tenantId, Collection<String> statuses);
    Optional<AccessGrant> findByTenantIdAndId(UUID tenantId, UUID id);
}
