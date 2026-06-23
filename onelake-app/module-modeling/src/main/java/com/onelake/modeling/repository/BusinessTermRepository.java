package com.onelake.modeling.repository;

import com.onelake.modeling.domain.entity.BusinessTerm;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BusinessTermRepository extends JpaRepository<BusinessTerm, UUID> {
    Optional<BusinessTerm> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<BusinessTerm> findByTenantIdAndCodeIgnoreCase(UUID tenantId, String code);

    List<BusinessTerm> findByTenantIdOrderByUpdatedAtDesc(UUID tenantId);
}
