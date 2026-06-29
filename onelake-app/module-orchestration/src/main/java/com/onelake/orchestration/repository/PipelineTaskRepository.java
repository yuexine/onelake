package com.onelake.orchestration.repository;

import com.onelake.orchestration.domain.entity.PipelineTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PipelineTaskRepository extends JpaRepository<PipelineTask, UUID> {

    List<PipelineTask> findByDagIdOrderByCreatedAtAsc(UUID dagId);

    List<PipelineTask> findByDagIdAndTaskType(UUID dagId, String taskType);

    Optional<PipelineTask> findByDagIdAndTaskKey(UUID dagId, String taskKey);

    Optional<PipelineTask> findByTenantIdAndModelId(UUID tenantId, UUID modelId);

    List<PipelineTask> findByTenantIdAndTaskType(UUID tenantId, String taskType);

    long countByTenantIdAndModelId(UUID tenantId, UUID modelId);
}
