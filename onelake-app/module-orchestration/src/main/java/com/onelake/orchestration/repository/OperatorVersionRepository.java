package com.onelake.orchestration.repository;

import com.onelake.orchestration.domain.entity.OperatorVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 算子版本持久化访问接口。
 */
public interface OperatorVersionRepository extends JpaRepository<OperatorVersion, UUID> {

    /** 按算子和语义版本定位不可变 Manifest。 */
    Optional<OperatorVersion> findByOperatorIdAndVersion(UUID operatorId, String version);

    /** 按发布时间倒序返回算子版本历史。 */
    List<OperatorVersion> findByOperatorIdOrderByCreatedAtDesc(UUID operatorId);
}
