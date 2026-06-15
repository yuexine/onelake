package com.onelake.security.repository;

import com.onelake.security.domain.entity.MaskingPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MaskingPolicyRepository extends JpaRepository<MaskingPolicy, UUID> {
    List<MaskingPolicy> findByTenantId(UUID tenantId);
    List<MaskingPolicy> findByTenantIdAndTargetFqn(UUID tenantId, String fqn);
    List<MaskingPolicy> findByTargetFqnOrderByPriorityDesc(String fqn);
}
