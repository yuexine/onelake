package com.onelake.orchestration.repository;

import com.onelake.orchestration.domain.entity.ResourceGroup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 资源组持久化访问接口。
 */
public interface ResourceGroupRepository extends JpaRepository<ResourceGroup, UUID> {

    List<ResourceGroup> findByTenantIdIsNullOrderByCodeAsc();

    List<ResourceGroup> findByTenantIdOrderByCodeAsc(UUID tenantId);

    Optional<ResourceGroup> findByTenantIdIsNullAndCode(String code);

    Optional<ResourceGroup> findByTenantIdAndCode(UUID tenantId, String code);
}
