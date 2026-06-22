CREATE TABLE IF NOT EXISTS modeling.model_run (
  id                         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id                  UUID NOT NULL,
  model_id                   UUID NOT NULL REFERENCES modeling.data_model(id) ON DELETE CASCADE,
  status                     VARCHAR(16) NOT NULL DEFAULT 'QUEUED',
  trigger_type               VARCHAR(32) NOT NULL DEFAULT 'MANUAL',
  source_integration_run_id  UUID,
  orchestration_dag_id       UUID,
  dagster_run_id             VARCHAR(128),
  engine_run_id              VARCHAR(128),
  trino_query_id             VARCHAR(128),
  resource_group             VARCHAR(64),
  compute_profile            VARCHAR(64),
  queued_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
  started_at                 TIMESTAMPTZ,
  finished_at                TIMESTAMPTZ,
  error_msg                  TEXT,
  rows_read                  BIGINT,
  rows_written               BIGINT,
  artifacts_path             VARCHAR(512),
  estimated_scan_bytes       BIGINT,
  actual_scan_bytes          BIGINT,
  cost_estimate              NUMERIC(18, 4),
  queue_reason               VARCHAR(256),
  retry_count                INT NOT NULL DEFAULT 0,
  created_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at                 TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_model_run_model_time
  ON modeling.model_run (model_id, queued_at DESC);

CREATE INDEX IF NOT EXISTS idx_model_run_tenant_status
  ON modeling.model_run (tenant_id, status, queued_at DESC);

CREATE INDEX IF NOT EXISTS idx_model_run_dagster
  ON modeling.model_run (dagster_run_id);
