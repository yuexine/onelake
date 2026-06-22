CREATE TABLE IF NOT EXISTS security.acl_resource (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id        UUID NOT NULL,
  resource_type    VARCHAR(32) NOT NULL,
  resource_id      UUID NOT NULL,
  grantee_type     VARCHAR(16) NOT NULL,
  grantee_id       UUID NOT NULL,
  permission       VARCHAR(16) NOT NULL,
  granted_by       UUID,
  granted_by_name  VARCHAR(128),
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (resource_type, resource_id, grantee_type, grantee_id, permission)
);
CREATE INDEX IF NOT EXISTS idx_acl_resource_lookup
  ON security.acl_resource (resource_type, resource_id);
CREATE INDEX IF NOT EXISTS idx_acl_grantee_lookup
  ON security.acl_resource (tenant_id, grantee_type, grantee_id, permission);

COMMENT ON TABLE security.acl_resource IS 'SavedQuery / QueryTemplate 等共享资源的 ACL（Sprint 5b）';
COMMENT ON COLUMN security.acl_resource.resource_type IS 'SAVED_QUERY | QUERY_TEMPLATE';
COMMENT ON COLUMN security.acl_resource.grantee_type IS 'USER | ROLE | GROUP';
COMMENT ON COLUMN security.acl_resource.permission IS 'VIEW | RUN | EDIT';
