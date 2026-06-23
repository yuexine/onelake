CREATE TABLE IF NOT EXISTS orchestration.operator (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id      UUID,
  operator_ref   VARCHAR(128) NOT NULL,
  category       VARCHAR(24) NOT NULL,
  scope          VARCHAR(16) NOT NULL,
  display_name   VARCHAR(128) NOT NULL,
  description    TEXT,
  latest_version VARCHAR(24) NOT NULL,
  status         VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (operator_ref, scope, tenant_id)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_operator_builtin_ref
  ON orchestration.operator (operator_ref)
  WHERE scope = 'BUILTIN' AND tenant_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_operator_tenant_scope
  ON orchestration.operator (tenant_id, scope, category, status);

CREATE TABLE IF NOT EXISTS orchestration.operator_version (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  operator_id UUID NOT NULL REFERENCES orchestration.operator(id) ON DELETE CASCADE,
  version     VARCHAR(24) NOT NULL,
  manifest    JSONB NOT NULL,
  changelog   TEXT,
  created_by  UUID,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (operator_id, version)
);

CREATE INDEX IF NOT EXISTS idx_operator_version_operator_time
  ON orchestration.operator_version (operator_id, created_at DESC);

CREATE TABLE IF NOT EXISTS orchestration.operator_install (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id      UUID NOT NULL,
  operator_id    UUID NOT NULL REFERENCES orchestration.operator(id) ON DELETE CASCADE,
  pinned_version VARCHAR(24),
  installed_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, operator_id)
);

CREATE INDEX IF NOT EXISTS idx_operator_install_tenant
  ON orchestration.operator_install (tenant_id);
