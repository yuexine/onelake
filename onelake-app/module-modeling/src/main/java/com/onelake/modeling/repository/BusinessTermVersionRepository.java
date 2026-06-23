package com.onelake.modeling.repository;

import com.onelake.modeling.domain.entity.BusinessTermVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BusinessTermVersionRepository extends JpaRepository<BusinessTermVersion, UUID> {
    List<BusinessTermVersion> findByTenantIdAndTermIdOrderByVersionDesc(UUID tenantId, UUID termId);
}
