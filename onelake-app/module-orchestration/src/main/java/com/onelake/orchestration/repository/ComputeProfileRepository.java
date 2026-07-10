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

    /** 按编码返回资源组下的全部计算规格。 */
    List<ComputeProfile> findByResourceGroupIdOrderByCodeAsc(UUID resourceGroupId);

    /** 批量加载多个资源组的规格，避免列表响应逐组查询。 */
    List<ComputeProfile> findByResourceGroupIdInOrderByCodeAsc(Collection<UUID> resourceGroupIds);

    /** 用组内稳定编码定位唯一规格。 */
    Optional<ComputeProfile> findByResourceGroupIdAndCode(UUID resourceGroupId, String code);
}
