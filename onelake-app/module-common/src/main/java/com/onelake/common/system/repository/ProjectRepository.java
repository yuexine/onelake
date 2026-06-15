package com.onelake.common.system.repository;

import com.onelake.common.system.entity.ProjectEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProjectRepository extends JpaRepository<ProjectEntity, UUID> {

    List<ProjectEntity> findByTenantIdOrderByNameAsc(UUID tenantId);

    boolean existsByTenantIdAndId(UUID tenantId, UUID id);
}
