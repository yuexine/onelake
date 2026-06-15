CREATE SCHEMA IF NOT EXISTS security;

CREATE TABLE IF NOT EXISTS security.secret (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   UUID NOT NULL,
  ref_key     VARCHAR(256) NOT NULL UNIQUE,
  kms_key_id  VARCHAR(128) NOT NULL,
  cipher_text BYTEA NOT NULL,
  rotated_at  TIMESTAMPTZ,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS security.masking_policy (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   UUID NOT NULL,
  target_fqn  VARCHAR(512) NOT NULL,
  classification VARCHAR(8),
  role_scope  VARCHAR(64),
  strategy    VARCHAR(16) NOT NULL,
  priority    INT NOT NULL DEFAULT 100,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS security.access_grant (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   UUID NOT NULL,
  subject_id  UUID NOT NULL,
  asset_fqn   VARCHAR(512) NOT NULL,
  columns     JSONB,
  permissions JSONB NOT NULL,
  status      VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  granted_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at  TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_grant_subject ON security.access_grant (subject_id, status);

CREATE TABLE IF NOT EXISTS security.role (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   UUID NOT NULL,
  code        VARCHAR(64) NOT NULL,
  name        VARCHAR(128) NOT NULL,
  description VARCHAR(256),
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, code)
);

CREATE TABLE IF NOT EXISTS security.role_binding (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   UUID NOT NULL,
  role_id     UUID NOT NULL REFERENCES security.role(id),
  resource_type VARCHAR(32) NOT NULL,
  resource_ref  VARCHAR(512) NOT NULL,
  actions     JSONB NOT NULL,
  UNIQUE (tenant_id, role_id, resource_type, resource_ref)
);

CREATE TABLE IF NOT EXISTS security.approval_request (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   UUID NOT NULL,
  request_type VARCHAR(32) NOT NULL,
  applicant_id UUID NOT NULL,
  target_ref  VARCHAR(512) NOT NULL,
  payload     JSONB,
  status      VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  approver_id UUID,
  comment     VARCHAR(512),
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  decided_at  TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_approval_status ON security.approval_request (tenant_id, status, created_at DESC);
