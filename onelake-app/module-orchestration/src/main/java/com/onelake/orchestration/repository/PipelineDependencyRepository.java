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
}
