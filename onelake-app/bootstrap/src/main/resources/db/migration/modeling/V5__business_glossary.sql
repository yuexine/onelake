CREATE TABLE IF NOT EXISTS modeling.business_term (
  id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id          UUID NOT NULL,
  code               VARCHAR(64) NOT NULL,
  name               VARCHAR(128) NOT NULL,
  domain_id          UUID REFERENCES modeling.subject_domain(id),
  definition         TEXT,
  caliber_sql        TEXT,
  synonyms           JSONB NOT NULL DEFAULT '[]'::jsonb,
  owner_id           UUID,
  owner_name         VARCHAR(128),
  steward_id         UUID,
  status             VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  version            INT NOT NULL DEFAULT 1,
  sensitivity_level  VARCHAR(8),
  tags               JSONB NOT NULL DEFAULT '[]'::jsonb,
  created_by         UUID,
  updated_by         UUID,
  approved_by        UUID,
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  approved_at        TIMESTAMPTZ,
  CONSTRAINT uq_business_term_tenant_code UNIQUE (tenant_id, code),
  CONSTRAINT ck_business_term_status CHECK (status IN ('DRAFT','REVIEWING','APPROVED','REJECTED','DEPRECATED','ARCHIVED'))
);

CREATE INDEX IF NOT EXISTS idx_business_term_tenant_status
  ON modeling.business_term (tenant_id, status);

CREATE INDEX IF NOT EXISTS idx_business_term_tenant_domain
  ON modeling.business_term (tenant_id, domain_id);

CREATE TABLE IF NOT EXISTS modeling.business_term_binding (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id      UUID NOT NULL,
  term_id        UUID NOT NULL REFERENCES modeling.business_term(id),
  asset_id       UUID,
  asset_fqn      VARCHAR(512) NOT NULL,
  column_name    VARCHAR(128),
  relation_type  VARCHAR(16) NOT NULL DEFAULT 'DEFINES',
  source         VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
  confidence     NUMERIC(5,2),
  status         VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_by     UUID,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT ck_business_term_binding_relation CHECK (relation_type IN ('DEFINES','USES','DERIVES')),
  CONSTRAINT ck_business_term_binding_source CHECK (source IN ('MANUAL','CATALOG','MODELING','IMPORT','SUGGESTED')),
  CONSTRAINT ck_business_term_binding_status CHECK (status IN ('ACTIVE','PENDING','REJECTED','STALE'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_business_term_binding
  ON modeling.business_term_binding (tenant_id, term_id, asset_fqn, coalesce(column_name, ''));

CREATE INDEX IF NOT EXISTS idx_business_term_binding_term
  ON modeling.business_term_binding (tenant_id, term_id, status);

CREATE INDEX IF NOT EXISTS idx_business_term_binding_asset
  ON modeling.business_term_binding (tenant_id, asset_fqn, status);

CREATE TABLE IF NOT EXISTS modeling.business_term_version (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id      UUID NOT NULL,
  term_id        UUID NOT NULL REFERENCES modeling.business_term(id),
  version        INT NOT NULL,
  snapshot       JSONB NOT NULL,
  change_reason  TEXT,
  changed_by     UUID,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_business_term_version UNIQUE (tenant_id, term_id, version)
);
