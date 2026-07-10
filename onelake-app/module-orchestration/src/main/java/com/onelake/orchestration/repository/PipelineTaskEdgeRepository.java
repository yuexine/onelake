package com.onelake.orchestration.repository;

import com.onelake.orchestration.domain.entity.PipelineTaskEdge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * 流水线边持久化访问接口。
 */
@Repository
public interface PipelineTaskEdgeRepository extends JpaRepository<PipelineTaskEdge, UUID> {

    /** 返回流水线全部边，供图校验、编译和运行拓扑构建。 */
    List<PipelineTaskEdge> findByDagId(UUID dagId);

    /** 按边层级筛选流水线连接。 */
    List<PipelineTaskEdge> findByDagIdAndEdgeLayer(UUID dagId, String edgeLayer);
}
