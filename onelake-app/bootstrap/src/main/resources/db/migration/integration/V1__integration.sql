-- =========================================================
-- integration schema: 数据源 / 同步任务 / 运行 / 源端 schema 快照
-- 对应《技术初始化文档》§7.2 + §7.9
-- =========================================================
CREATE SCHEMA IF NOT EXISTS integration;

CREATE TABLE IF NOT EXISTS integration.datasource (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   UUID NOT NULL,
  project_id  UUID,
  name        VARCHAR(128) NOT NULL,
  type        VARCHAR(32) NOT NULL,
  config      JSONB NOT NULL,
  secret_ref  VARCHAR(256),
  network_mode VARCHAR(32) DEFAULT 'DIRECT',
  env_level   VARCHAR(16) DEFAULT 'PROD',
  health      VARCHAR(16) DEFAULT 'UNKNOWN',
  last_check_at TIMESTAMPTZ,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, name)
);

CREATE TABLE IF NOT EXISTS integration.sync_task (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   UUID NOT NULL,
  source_id   UUID NOT NULL REFERENCES integration.datasource(id),
  name        VARCHAR(128) NOT NULL,
  mode        VARCHAR(16) NOT NULL,
  target_table VARCHAR(256) NOT NULL,
  field_mapping JSONB,
  airbyte_connection_id VARCHAR(64),
  schedule_cron VARCHAR(64),
  rate_limit  INT,
  dirty_threshold INT DEFAULT 0,
  status      VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_synctask_source ON integration.sync_task (source_id);

CREATE TABLE IF NOT EXISTS integration.sync_run (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  task_id     UUID NOT NULL REFERENCES integration.sync_task(id),
  external_job_id VARCHAR(64),
  status      VARCHAR(16) NOT NULL,
  rows_read   BIGINT DEFAULT 0,
  rows_written BIGINT DEFAULT 0,
  error_code  VARCHAR(64),
  error_msg   TEXT,
  checkpoint  JSONB,
  started_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  finished_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_syncrun_task_time ON integration.sync_run (task_id, started_at DESC);

CREATE TABLE IF NOT EXISTS integration.source_schema_snapshot (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  source_id   UUID NOT NULL REFERENCES integration.datasource(id),
  object_name VARCHAR(256) NOT NULL,
  columns     JSONB NOT NULL,
  checksum    VARCHAR(64) NOT NULL,
  captured_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_snapshot_source ON integration.source_schema_snapshot (source_id, captured_at DESC);
