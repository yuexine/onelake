package com.onelake.security.repository;

import com.onelake.security.domain.entity.AclResource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AclResourceRepository extends JpaRepository<AclResource, UUID> {

    List<AclResource> findByTenantIdAndResourceTypeAndResourceId(UUID tenantId, String resourceType, UUID resourceId);

    /**
     * 返回当前用户有指定权限的所有资源 ID。
     * 命中条件：
     *   ① 显式给 USER 授权
     *   ② 给 ROLE 授权，且 role 在用户当前角色集合中
     */
    @Query("""
        SELECT DISTINCT a.resourceId FROM AclResource a
        WHERE a.tenantId = :tenantId
          AND a.resourceType = :resourceType
          AND a.permission = :permission
          AND (
            (a.granteeType = 'USER' AND a.granteeId = :userId)
            OR (a.granteeType = 'ROLE' AND a.granteeId IN :roleIds)
          )
        """)
    List<UUID> findVisibleResourceIds(
        UUID tenantId,
        String resourceType,
        String permission,
        UUID userId,
        List<UUID> roleIds
    );

    @Modifying
    @Query("DELETE FROM AclResource a WHERE a.tenantId = :tenantId AND a.resourceType = :resourceType AND a.resourceId = :resourceId")
    int deleteByResource(UUID tenantId, String resourceType, UUID resourceId);

    @Modifying
    @Query("""
        DELETE FROM AclResource a
        WHERE a.tenantId = :tenantId
          AND a.resourceType = :resourceType
          AND a.resourceId = :resourceId
          AND a.permission = :permission
        """)
    int deleteByResourceAndPermission(UUID tenantId, String resourceType, UUID resourceId, String permission);

    boolean existsByTenantIdAndResourceTypeAndResourceIdAndGranteeTypeAndGranteeIdAndPermission(
        UUID tenantId, String resourceType, UUID resourceId,
        String granteeType, UUID granteeId, String permission
    );
}
