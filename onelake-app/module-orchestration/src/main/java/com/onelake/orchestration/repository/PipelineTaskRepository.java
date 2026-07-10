package com.onelake.orchestration.repository;

import com.onelake.orchestration.domain.entity.PipelineTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 流水线节点持久化访问接口。
 */
@Repository
public interface PipelineTaskRepository extends JpaRepository<PipelineTask, UUID> {

    /** 按创建顺序返回流水线节点，供编辑器和无显式拓扑时保持稳定顺序。 */
    List<PipelineTask> findByDagIdOrderByCreatedAtAsc(UUID dagId);

    /** 查询流水线内指定类型节点。 */
    List<PipelineTask> findByDagIdAndTaskType(UUID dagId, String taskType);

    /** 用 DAG + taskKey 定位节点；taskKey 是流水线内业务唯一键。 */
    Optional<PipelineTask> findByDagIdAndTaskKey(UUID dagId, String taskKey);

    /** 兼容历史模型迁移时查询已引用该 modelId 的节点。 */
    Optional<PipelineTask> findByTenantIdAndModelId(UUID tenantId, UUID modelId);

    /** 查询租户指定类型节点，主要用于事件触发订阅匹配。 */
    List<PipelineTask> findByTenantIdAndTaskType(UUID tenantId, String taskType);

    /** 历史模型迁移幂等检查。 */
    long countByTenantIdAndModelId(UUID tenantId, UUID modelId);
}
