package com.onelake.orchestration.repository;

import com.onelake.orchestration.domain.entity.ResourceGroup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 资源组持久化访问接口。
 */
public interface ResourceGroupRepository extends JpaRepository<ResourceGroup, UUID> {

    /** 查询平台级默认资源组。 */
    List<ResourceGroup> findByTenantIdIsNullOrderByCodeAsc();

    /** 查询租户私有资源组。 */
    List<ResourceGroup> findByTenantIdOrderByCodeAsc(UUID tenantId);

    /** 按编码查询平台级默认组。 */
    Optional<ResourceGroup> findByTenantIdIsNullAndCode(String code);

    /** 按编码查询租户私有组。 */
    Optional<ResourceGroup> findByTenantIdAndCode(UUID tenantId, String code);
}
