package com.onelake.dataservice.repository;

import com.onelake.dataservice.domain.entity.ApiDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiDefinitionRepository extends JpaRepository<ApiDefinition, UUID> {
    List<ApiDefinition> findByTenantId(UUID tenantId);
    Optional<ApiDefinition> findByTenantIdAndId(UUID tenantId, UUID id);
    Optional<ApiDefinition> findByTenantIdAndApiPath(UUID tenantId, String apiPath);
    Optional<ApiDefinition> findByApiPath(String apiPath);
}
