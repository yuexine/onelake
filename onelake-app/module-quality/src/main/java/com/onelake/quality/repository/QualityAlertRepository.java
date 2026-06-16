package com.onelake.quality.repository;

import com.onelake.quality.domain.entity.Alert;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface QualityAlertRepository extends JpaRepository<Alert, UUID> {
    List<Alert> findByTenantIdAndStatus(UUID tenantId, String status);
}
