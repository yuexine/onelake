package com.onelake.modeling.repository;

import com.onelake.modeling.domain.entity.SubjectDomain;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SubjectDomainRepository extends JpaRepository<SubjectDomain, UUID> {
    List<SubjectDomain> findByTenantId(UUID tenantId);
}
