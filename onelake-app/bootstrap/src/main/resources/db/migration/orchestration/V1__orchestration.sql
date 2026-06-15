CREATE SCHEMA IF NOT EXISTS orchestration;

CREATE TABLE IF NOT EXISTS orchestration.dag (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   UUID NOT NULL,
  name        VARCHAR(128) NOT NULL,
  dagster_job VARCHAR(128) NOT NULL,
  definition  JSONB NOT NULL,
  schedule_cron VARCHAR(64),
  enabled     BOOLEAN NOT NULL DEFAULT true,
  version     INT NOT NULL DEFAULT 1,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, name)
);

CREATE TABLE IF NOT EXISTS orchestration.job_run (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  dag_id      UUID NOT NULL REFERENCES orchestration.dag(id),
  dagster_run_id VARCHAR(64),
  trigger_type VARCHAR(16) NOT NULL,
  status      VARCHAR(16) NOT NULL,
  started_at  TIMESTAMPTZ,
  finished_at TIMESTAMPTZ,
  triggered_by UUID
);
CREATE INDEX IF NOT EXISTS idx_jobrun_dag_time ON orchestration.job_run (dag_id, started_at DESC);
