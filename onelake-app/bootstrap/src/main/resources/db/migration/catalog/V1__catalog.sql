CREATE SCHEMA IF NOT EXISTS catalog;

CREATE TABLE IF NOT EXISTS catalog.asset (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   UUID NOT NULL,
  om_fqn      VARCHAR(512) NOT NULL,
  asset_type  VARCHAR(32) NOT NULL,
  layer       VARCHAR(8),
  display_name VARCHAR(256),
  description TEXT,
  owner_id    UUID,
  tags        JSONB DEFAULT '[]',
  classification VARCHAR(8),
  quality_score NUMERIC(5,2),
  synced_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, om_fqn)
);
CREATE INDEX IF NOT EXISTS idx_asset_layer ON catalog.asset (tenant_id, layer);

CREATE TABLE IF NOT EXISTS catalog.lineage_edge (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   UUID NOT NULL,
  upstream_fqn VARCHAR(512) NOT NULL,
  downstream_fqn VARCHAR(512) NOT NULL,
  column_level JSONB,
  job_ref     VARCHAR(256),
  synced_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, upstream_fqn, downstream_fqn)
);
