package com.onelake.integration.repository;

import com.onelake.integration.domain.entity.SyncTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SyncTaskRepository extends JpaRepository<SyncTask, UUID> {

    List<SyncTask> findBySourceId(UUID sourceId);

    List<SyncTask> findByTenantId(UUID tenantId);
}
