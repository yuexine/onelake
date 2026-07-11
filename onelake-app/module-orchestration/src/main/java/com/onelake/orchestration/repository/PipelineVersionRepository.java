package com.onelake.orchestration.repository;

import com.onelake.orchestration.domain.entity.PipelineVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 流水线不可变发布版本持久化访问接口。
 */
public interface PipelineVersionRepository extends JpaRepository<PipelineVersion, UUID> {

    /** 按版本号倒序返回 DAG 的发布历史。 */
    List<PipelineVersion> findByDagIdOrderByVersionDesc(UUID dagId);

    /** 按 DAG 和版本号定位一个不可变快照。 */
    Optional<PipelineVersion> findByDagIdAndVersion(UUID dagId, Integer version);
}
