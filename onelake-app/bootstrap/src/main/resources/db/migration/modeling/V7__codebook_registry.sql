CREATE TABLE IF NOT EXISTS modeling.codebook (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id        UUID NOT NULL,
  code             VARCHAR(96) NOT NULL,
  name             VARCHAR(128) NOT NULL,
  domain           VARCHAR(64),
  description      TEXT,
  status           VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  latest_version   VARCHAR(32),
  no_match_policy  VARCHAR(16) NOT NULL DEFAULT 'KEEP',
  entries          JSONB NOT NULL DEFAULT '[]'::jsonb,
  tags             JSONB NOT NULL DEFAULT '[]'::jsonb,
  created_by       UUID,
  updated_by       UUID,
  published_by     UUID,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  published_at     TIMESTAMPTZ,
  CONSTRAINT uq_codebook_tenant_code UNIQUE (tenant_id, code),
  CONSTRAINT ck_codebook_status CHECK (status IN ('DRAFT','PUBLISHED','DEPRECATED','ARCHIVED')),
  CONSTRAINT ck_codebook_no_match_policy CHECK (no_match_policy IN ('KEEP','NULL','FAIL'))
);

CREATE INDEX IF NOT EXISTS idx_codebook_tenant_status
  ON modeling.codebook (tenant_id, status);

CREATE INDEX IF NOT EXISTS idx_codebook_tenant_domain
  ON modeling.codebook (tenant_id, domain);

CREATE TABLE IF NOT EXISTS modeling.codebook_version (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id      UUID NOT NULL,
  codebook_id    UUID NOT NULL REFERENCES modeling.codebook(id),
  version        VARCHAR(32) NOT NULL,
  entries        JSONB NOT NULL,
  snapshot       JSONB NOT NULL,
  change_reason  TEXT,
  published_by   UUID,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_codebook_version UNIQUE (tenant_id, codebook_id, version)
);

CREATE INDEX IF NOT EXISTS idx_codebook_version_codebook
  ON modeling.codebook_version (tenant_id, codebook_id, created_at DESC);
