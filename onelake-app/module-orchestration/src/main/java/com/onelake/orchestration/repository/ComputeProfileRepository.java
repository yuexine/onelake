package com.onelake.orchestration.repository;

import com.onelake.orchestration.domain.entity.ComputeProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ComputeProfileRepository extends JpaRepository<ComputeProfile, UUID> {

    List<ComputeProfile> findByResourceGroupIdOrderByCodeAsc(UUID resourceGroupId);

    List<ComputeProfile> findByResourceGroupIdInOrderByCodeAsc(Collection<UUID> resourceGroupIds);

    Optional<ComputeProfile> findByResourceGroupIdAndCode(UUID resourceGroupId, String code);
}
