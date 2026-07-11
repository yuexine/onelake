package com.onelake.orchestration.repository;

import com.onelake.orchestration.domain.entity.PipelineParam;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** 流水线三级参数持久化接口。 */
@Repository
public interface PipelineParamRepository extends JpaRepository<PipelineParam, UUID> {

    /** 回滚版本时只替换当前 Pipeline 的 PIPELINE/TASK 草稿参数。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            delete from PipelineParam p
             where p.tenantId = :tenantId
               and p.dagId = :dagId
               and p.scope in ('PIPELINE', 'TASK')
            """)
    int deleteDraftByTenantIdAndDagId(@Param("tenantId") UUID tenantId,
                                      @Param("dagId") UUID dagId);

    /** Hibernate 插入回滚参数后，把生成主键恢复为不可变快照中的技术主键。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE orchestration.pipeline_param
               SET id = :snapshotId
             WHERE id = :generatedId
               AND tenant_id = :tenantId
               AND dag_id = :dagId
            """, nativeQuery = true)
    int restoreSnapshotId(@Param("tenantId") UUID tenantId,
                          @Param("dagId") UUID dagId,
                          @Param("generatedId") UUID generatedId,
                          @Param("snapshotId") UUID snapshotId);

    /** 查询租户的全局作用域参数。 */
    List<PipelineParam> findByTenantIdAndScope(UUID tenantId, String scope);

    /** 查询租户内指定流水线的流水线作用域参数。 */
    List<PipelineParam> findByTenantIdAndDagIdAndScope(
            UUID tenantId, UUID dagId, String scope);

    /** 查询租户内指定流水线节点的节点作用域参数。 */
    List<PipelineParam> findByTenantIdAndDagIdAndTaskKeyAndScope(
            UUID tenantId, UUID dagId, String taskKey, String scope);

    /** 删除节点时同步清理其参数，避免 task_key 字符串引用形成孤儿记录。 */
    long deleteByTenantIdAndDagIdAndTaskKeyAndScope(
            UUID tenantId, UUID dagId, String taskKey, String scope);

    /** 单次读取一条流水线运行所需的全局、流水线和指定节点参数。 */
    @Query("""
            select p from PipelineParam p
            where p.tenantId = :tenantId
              and (
                p.scope = 'GLOBAL'
                or (p.scope = 'PIPELINE' and p.dagId = :dagId)
                or (p.scope = 'TASK' and p.dagId = :dagId and p.taskKey in :taskKeys)
              )
            """)
    List<PipelineParam> findForResolution(
            @Param("tenantId") UUID tenantId,
            @Param("dagId") UUID dagId,
            @Param("taskKeys") Collection<String> taskKeys);
}
