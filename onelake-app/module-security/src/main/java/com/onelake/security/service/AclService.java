package com.onelake.security.service;

import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.security.domain.entity.AclResource;
import com.onelake.security.repository.AclResourceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 共享资源 ACL 服务（Sprint 5b）。
 *
 * 权限判定：
 *   ① owner（resource.ownerId == 当前用户）→ 自动获得 VIEW/EDIT
 *   ② ACL 显式授权：USER（按 userId）或 ROLE（按 roleId）
 *
 * shared=true 自动授权策略：
 *   向 ROLE_DE 发 VIEW 权限（grantee_id = ROLE_DE_UUID 常量）
 *
 * shared=false 回收：
 *   清除该资源的所有 ACL 条目（owner 权限来自 ownerId，不依赖 ACL）
 *
 * 资源删除：
 *   清除该资源的所有 ACL 条目
 */
@Service
@RequiredArgsConstructor
public class AclService {

    /** 固定 UUID 代表 ROLE_DE；keycloak-realm.sh 创建的角色与此常量一一对应。 */
    public static final UUID ROLE_DE_UUID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    public static final String RESOURCE_SAVED_QUERY = "SAVED_QUERY";
    public static final String RESOURCE_QUERY_TEMPLATE = "QUERY_TEMPLATE";

    public static final String PERM_VIEW = "VIEW";
    public static final String PERM_RUN = "RUN";
    public static final String PERM_EDIT = "EDIT";

    private static final String GRANTEE_ROLE = "ROLE";
    private static final String GRANTEE_USER = "USER";

    private final AclResourceRepository repo;

    @Transactional(readOnly = true)
    public boolean canView(String resourceType, UUID resourceId, UUID ownerId) {
        return isOwner(ownerId) || hasPermission(resourceType, resourceId, PERM_VIEW);
    }

    @Transactional(readOnly = true)
    public boolean canEdit(String resourceType, UUID resourceId, UUID ownerId) {
        return isOwner(ownerId) || hasPermission(resourceType, resourceId, PERM_EDIT);
    }

    @Transactional(readOnly = true)
    public void requireEdit(String resourceType, UUID resourceId, UUID ownerId) {
        if (!canEdit(resourceType, resourceId, ownerId)) {
            throw new BizException(40302, "当前账号无编辑权限（仅 owner 或获 EDIT 授权的用户可操作）");
        }
    }

    @Transactional(readOnly = true)
    public <T> List<T> filterViewable(List<T> resources, String resourceType, ResourceAccessor<T> accessor) {
        UUID userId = TenantContext.getUserId();
        Set<UUID> viewableIds = new HashSet<>(collectVisibleIds(resourceType, PERM_VIEW));
        List<T> result = new java.util.ArrayList<>(resources.size());
        for (T r : resources) {
            UUID ownerId = accessor.ownerId(r);
            UUID resId = accessor.resourceId(r);
            if (userId != null && userId.equals(ownerId)) {
                result.add(r);
            } else if (viewableIds.contains(resId)) {
                result.add(r);
            }
        }
        return result;
    }

    /**
     * 资源 shared 从 false 变 true 时调用：自动给 ROLE_DE 发 VIEW。
     * 幂等：如果已存在则跳过。
     */
    @Transactional
    public void autoGrantOnShared(String resourceType, UUID resourceId) {
        UUID tenantId = TenantContext.getTenantId();
        if (repo.existsByTenantIdAndResourceTypeAndResourceIdAndGranteeTypeAndGranteeIdAndPermission(
            tenantId, resourceType, resourceId, GRANTEE_ROLE, ROLE_DE_UUID, PERM_VIEW
        )) {
            return;
        }
        AclResource grant = new AclResource();
        grant.setTenantId(tenantId);
        grant.setResourceType(resourceType);
        grant.setResourceId(resourceId);
        grant.setGranteeType(GRANTEE_ROLE);
        grant.setGranteeId(ROLE_DE_UUID);
        grant.setPermission(PERM_VIEW);
        grant.setGrantedBy(TenantContext.getUserId());
        grant.setGrantedByName(TenantContext.getUsername());
        repo.save(grant);
    }

    /**
     * 资源 shared 从 true 变 false 时调用：清除所有非 owner ACL。
     */
    @Transactional
    public void autoRevokeOnPrivate(String resourceType, UUID resourceId) {
        repo.deleteByResource(TenantContext.getTenantId(), resourceType, resourceId);
    }

    /**
     * 资源删除时调用：清理全部 ACL。
     */
    @Transactional
    public void cleanupOnDelete(String resourceType, UUID resourceId) {
        repo.deleteByResource(TenantContext.getTenantId(), resourceType, resourceId);
    }

    private boolean isOwner(UUID ownerId) {
        UUID userId = TenantContext.getUserId();
        return userId != null && ownerId != null && userId.equals(ownerId);
    }

    private boolean hasPermission(String resourceType, UUID resourceId, String permission) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        if (tenantId == null || userId == null) return false;
        List<UUID> roleIds = currentUserRoleIds();
        List<UUID> hits = repo.findVisibleResourceIds(tenantId, resourceType, permission, userId, roleIds);
        return !hits.isEmpty();
    }

    private List<UUID> collectVisibleIds(String resourceType, String permission) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        if (tenantId == null || userId == null) return List.of();
        return repo.findVisibleResourceIds(tenantId, resourceType, permission, userId, currentUserRoleIds());
    }

    private List<UUID> currentUserRoleIds() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return List.of();
        List<UUID> ids = new java.util.ArrayList<>();
        for (GrantedAuthority ga : auth.getAuthorities()) {
            String name = ga.getAuthority();
            // Spring 默认前缀 ROLE_，这里映射到固定 UUID
            if ("ROLE_DE".equals(name)) ids.add(ROLE_DE_UUID);
            // 其他角色暂未分配 UUID；预留扩展位
        }
        return ids;
    }

    public interface ResourceAccessor<T> {
        UUID ownerId(T resource);
        UUID resourceId(T resource);
    }
}
