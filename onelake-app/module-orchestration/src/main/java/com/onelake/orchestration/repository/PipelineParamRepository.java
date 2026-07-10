package com.onelake.orchestration.repository;

import com.onelake.orchestration.domain.entity.PipelineParam;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/** 流水线三级参数持久化接口。 */
@Repository
public interface PipelineParamRepository extends JpaRepository<PipelineParam, UUID> {

    /** 查询租户的全局作用域参数。 */
    List<PipelineParam> findByTenantIdAndScope(UUID tenantId, String scope);

    /** 查询租户内指定流水线的流水线作用域参数。 */
    List<PipelineParam> findByTenantIdAndDagIdAndScope(
            UUID tenantId, UUID dagId, String scope);

    /** 查询租户内指定流水线节点的节点作用域参数。 */
    List<PipelineParam> findByTenantIdAndDagIdAndTaskKeyAndScope(
            UUID tenantId, UUID dagId, String taskKey, String scope);
}
