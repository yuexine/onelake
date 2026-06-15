CREATE SCHEMA IF NOT EXISTS modeling;

CREATE TABLE IF NOT EXISTS modeling.subject_domain (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   UUID NOT NULL,
  code        VARCHAR(64) NOT NULL,
  name        VARCHAR(128) NOT NULL,
  parent_id   UUID,
  UNIQUE (tenant_id, code)
);

CREATE TABLE IF NOT EXISTS modeling.data_standard (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   UUID NOT NULL,
  term        VARCHAR(128) NOT NULL,
  naming_rule VARCHAR(256),
  code_rule   JSONB,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS modeling.metric (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   UUID NOT NULL,
  domain_id   UUID REFERENCES modeling.subject_domain(id),
  code        VARCHAR(64) NOT NULL,
  name        VARCHAR(128) NOT NULL,
  metric_type VARCHAR(16) NOT NULL,
  caliber_sql TEXT,
  dbt_model   VARCHAR(128),
  version     INT NOT NULL DEFAULT 1,
  UNIQUE (tenant_id, code)
);

CREATE TABLE IF NOT EXISTS modeling.dimension (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   UUID NOT NULL,
  domain_id   UUID REFERENCES modeling.subject_domain(id),
  code        VARCHAR(64) NOT NULL,
  name        VARCHAR(128) NOT NULL,
  dim_type    VARCHAR(16) NOT NULL,
  attributes  JSONB,
  UNIQUE (tenant_id, code)
);
