package com.onelake.analytics.repository;

import com.onelake.analytics.domain.entity.Dashboard;
import com.onelake.analytics.domain.enums.DashboardStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DashboardRepository extends JpaRepository<Dashboard, UUID> {

    Optional<Dashboard> findByIdAndTenantId(UUID id, UUID tenantId);

    List<Dashboard> findByTenantIdAndStatus(UUID tenantId, DashboardStatus status);

    List<Dashboard> findByTenantId(UUID tenantId);
}
