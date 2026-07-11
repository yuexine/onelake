package com.onelake.orchestration.repository;

import com.onelake.orchestration.domain.entity.PipelineTaskEdge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * 流水线边持久化访问接口。
 */
@Repository
public interface PipelineTaskEdgeRepository extends JpaRepository<PipelineTaskEdge, UUID> {

    /** 回滚版本时整组删除当前 DEV 草稿边。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from PipelineTaskEdge edge where edge.dagId = :dagId")
    int deleteDraftByDagId(@Param("dagId") UUID dagId);

    /** Hibernate 插入回滚边后，把生成主键恢复为不可变快照中的技术主键。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE orchestration.pipeline_task_edge
               SET id = :snapshotId
             WHERE id = :generatedId
               AND dag_id = :dagId
            """, nativeQuery = true)
    int restoreSnapshotId(@Param("dagId") UUID dagId,
                          @Param("generatedId") UUID generatedId,
                          @Param("snapshotId") UUID snapshotId);

    /** 返回流水线全部边，供图校验、编译和运行拓扑构建。 */
    List<PipelineTaskEdge> findByDagId(UUID dagId);

    /** 按边层级筛选流水线连接。 */
    List<PipelineTaskEdge> findByDagIdAndEdgeLayer(UUID dagId, String edgeLayer);
}
