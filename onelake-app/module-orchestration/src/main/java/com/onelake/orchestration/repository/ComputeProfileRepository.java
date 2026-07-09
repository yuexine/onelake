package com.onelake.orchestration.repository;

import com.onelake.orchestration.domain.entity.ComputeProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 计算画像持久化访问接口。
 */
public interface ComputeProfileRepository extends JpaRepository<ComputeProfile, UUID> {

    List<ComputeProfile> findByResourceGroupIdOrderByCodeAsc(UUID resourceGroupId);

    List<ComputeProfile> findByResourceGroupIdInOrderByCodeAsc(Collection<UUID> resourceGroupIds);

    Optional<ComputeProfile> findByResourceGroupIdAndCode(UUID resourceGroupId, String code);
}
