-- 回填：shared=true 的 SavedQuery / QueryTemplate 自动给 ROLE_DE 发 VIEW 权限
-- 注：ROLE_DE 的 grantee_id 使用固定 UUID（与 keycloak-realm.sh 创建的 ROLE_DE 对应）
-- 各租户独立执行，grantee_id 用 tenant_id 派生以保证跨租户唯一

INSERT INTO security.acl_resource (tenant_id, resource_type, resource_id, grantee_type, grantee_id, permission, granted_by_name, created_at)
SELECT tenant_id, 'SAVED_QUERY', id, 'ROLE', '00000000-0000-0000-0000-000000000001', 'VIEW', 'system-migration', now()
FROM catalog.saved_query
WHERE shared = true
  AND NOT EXISTS (
    SELECT 1 FROM security.acl_resource a
    WHERE a.resource_type = 'SAVED_QUERY'
      AND a.resource_id = catalog.saved_query.id
      AND a.grantee_type = 'ROLE'
      AND a.grantee_id = '00000000-0000-0000-0000-000000000001'
      AND a.permission = 'VIEW'
  );

INSERT INTO security.acl_resource (tenant_id, resource_type, resource_id, grantee_type, grantee_id, permission, granted_by_name, created_at)
SELECT tenant_id, 'QUERY_TEMPLATE', id, 'ROLE', '00000000-0000-0000-0000-000000000001', 'VIEW', 'system-migration', now()
FROM catalog.query_template
WHERE shared = true
  AND NOT EXISTS (
    SELECT 1 FROM security.acl_resource a
    WHERE a.resource_type = 'QUERY_TEMPLATE'
      AND a.resource_id = catalog.query_template.id
      AND a.grantee_type = 'ROLE'
      AND a.grantee_id = '00000000-0000-0000-0000-000000000001'
      AND a.permission = 'VIEW'
  );
