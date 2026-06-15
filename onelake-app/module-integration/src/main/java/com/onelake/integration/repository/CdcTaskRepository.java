package com.onelake.integration.repository;

import com.onelake.integration.domain.entity.CdcTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CdcTaskRepository extends JpaRepository<CdcTask, UUID> {
    List<CdcTask> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);
    List<CdcTask> findByTenantIdAndStatus(UUID tenantId, CdcTask.CdcStatus status);
}
