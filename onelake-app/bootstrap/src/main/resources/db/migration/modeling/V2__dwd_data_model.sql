CREATE TABLE IF NOT EXISTS modeling.data_model (
  id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id             UUID NOT NULL,
  name                  VARCHAR(128) NOT NULL,
  layer                 VARCHAR(16) NOT NULL,
  domain                VARCHAR(64),
  source_fqn            VARCHAR(256) NOT NULL,
  target_fqn            VARCHAR(256) NOT NULL,
  status                VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  materialization       VARCHAR(32) NOT NULL DEFAULT 'TABLE',
  unique_key            VARCHAR(128),
  incremental_column    VARCHAR(128),
  partition_expr        VARCHAR(256),
  sql_text              TEXT,
  compiled_sql          TEXT,
  dbt_model_name        VARCHAR(128),
  orchestration_dag_id  UUID,
  dagster_job           VARCHAR(128),
  artifact_path         VARCHAR(512),
  last_run_id           UUID,
  owner_id              UUID,
  owner_name            VARCHAR(128),
  created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, target_fqn)
);

CREATE INDEX IF NOT EXISTS idx_data_model_source
  ON modeling.data_model (tenant_id, source_fqn);

CREATE INDEX IF NOT EXISTS idx_data_model_status
  ON modeling.data_model (tenant_id, status);

CREATE TABLE IF NOT EXISTS modeling.data_model_source (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  model_id    UUID NOT NULL REFERENCES modeling.data_model(id) ON DELETE CASCADE,
  source_fqn  VARCHAR(256) NOT NULL,
  source_type VARCHAR(32) NOT NULL DEFAULT 'ODS_TABLE',
  sort_no     INT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_data_model_source_model
  ON modeling.data_model_source (model_id);

CREATE TABLE IF NOT EXISTS modeling.data_model_column_mapping (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  model_id        UUID NOT NULL REFERENCES modeling.data_model(id) ON DELETE CASCADE,
  source_column   VARCHAR(128) NOT NULL,
  target_column   VARCHAR(128) NOT NULL,
  source_type     VARCHAR(128),
  target_type     VARCHAR(128),
  expression      TEXT,
  primary_key     BOOLEAN NOT NULL DEFAULT false,
  classification  VARCHAR(16),
  pii_type        VARCHAR(64),
  suggest_level   VARCHAR(16),
  sort_no         INT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_data_model_mapping_model
  ON modeling.data_model_column_mapping (model_id, sort_no);
