package com.onelake.integration.repository;

import com.onelake.integration.domain.entity.DataSource;
import com.onelake.integration.domain.enums.DataSourceType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DataSourceRepository extends JpaRepository<DataSource, UUID> {

    Optional<DataSource> findByTenantIdAndName(UUID tenantId, String name);

    List<DataSource> findByTenantIdAndTypeIgnoreCase(UUID tenantId, DataSourceType type);

    List<DataSource> findByTenantId(UUID tenantId);
}
