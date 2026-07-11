package com.onelake.orchestration.repository;

import com.onelake.orchestration.domain.entity.PipelineTask;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 流水线节点持久化访问接口。
 */
@Repository
public interface PipelineTaskRepository extends JpaRepository<PipelineTask, UUID> {

    /** 回滚版本时整组删除当前 DEV 草稿节点。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from PipelineTask pt where pt.dagId = :dagId")
    int deleteDraftByDagId(@Param("dagId") UUID dagId);

    /** Hibernate 插入回滚节点后，把生成主键恢复为不可变快照中的技术主键。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE orchestration.pipeline_task
               SET id = :snapshotId
             WHERE id = :generatedId
               AND dag_id = :dagId
            """, nativeQuery = true)
    int restoreSnapshotId(@Param("dagId") UUID dagId,
                          @Param("generatedId") UUID generatedId,
                          @Param("snapshotId") UUID snapshotId);

    /** 按创建顺序返回流水线节点，供编辑器和无显式拓扑时保持稳定顺序。 */
    List<PipelineTask> findByDagIdOrderByCreatedAtAsc(UUID dagId);

    /** 查询流水线内指定类型节点。 */
    List<PipelineTask> findByDagIdAndTaskType(UUID dagId, String taskType);

    /** 用 DAG + taskKey 定位节点；taskKey 是流水线内业务唯一键。 */
    Optional<PipelineTask> findByDagIdAndTaskKey(UUID dagId, String taskKey);

    /**
     * 锁定流水线节点，串行化节点删除与节点级参数替换。
     *
     * <p>pipeline_param 以 task_key 软关联节点，保存 TASK 参数前必须持有节点行锁，
     * 确保并发删除会在参数保存完成后执行清理，或让保存方观察到节点已经不存在。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select pt from PipelineTask pt where pt.dagId = :dagId and pt.taskKey = :taskKey")
    Optional<PipelineTask> findByDagIdAndTaskKeyForUpdate(@Param("dagId") UUID dagId,
                                                          @Param("taskKey") String taskKey);

    /** 兼容历史模型迁移时查询已引用该 modelId 的节点。 */
    Optional<PipelineTask> findByTenantIdAndModelId(UUID tenantId, UUID modelId);

    /** 查询租户指定类型节点，主要用于事件触发订阅匹配。 */
    List<PipelineTask> findByTenantIdAndTaskType(UUID tenantId, String taskType);

    /** 历史模型迁移幂等检查。 */
    long countByTenantIdAndModelId(UUID tenantId, UUID modelId);
}
