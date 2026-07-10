package com.onelake.orchestration.repository;

import com.onelake.orchestration.domain.entity.PipelineDependency;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** 流水线周期依赖持久化接口。 */
public interface PipelineDependencyRepository extends JpaRepository<PipelineDependency, UUID> {

    /** 按稳定创建顺序读取一个下游的启用依赖。 */
    List<PipelineDependency> findByDownstreamDagIdAndEnabledTrueOrderByCreatedAtAsc(
            UUID downstreamDagId);

    /** 在租户边界内读取一个下游的全部依赖，供管理接口展示。 */
    List<PipelineDependency> findByDownstreamDagIdAndTenantIdOrderByCreatedAtAsc(
            UUID downstreamDagId, UUID tenantId);

    /** 读取租户内全部启用边，供新增依赖前执行传递性成环校验。 */
    List<PipelineDependency> findByTenantIdAndEnabledTrue(UUID tenantId);
}
