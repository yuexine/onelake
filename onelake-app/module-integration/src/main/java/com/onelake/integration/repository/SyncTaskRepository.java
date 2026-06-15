package com.onelake.integration.repository;

import com.onelake.integration.domain.entity.SyncTask;
import com.onelake.integration.domain.enums.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.UUID;

public interface SyncTaskRepository extends JpaRepository<SyncTask, UUID>, JpaSpecificationExecutor<SyncTask> {

    List<SyncTask> findBySourceId(UUID sourceId);

    List<SyncTask> findByTenantId(UUID tenantId);

    boolean existsBySourceId(UUID sourceId);

    boolean existsByTenantIdAndName(UUID tenantId, String name);
}
