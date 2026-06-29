-- P1: 流水线 v2 任务实体（pipeline_task / pipeline_task_edge / task_run）
-- 详见 docs/流水线模块重设计方案.md §6.1（C1 单源真相、C2 三层依赖）

CREATE TABLE IF NOT EXISTS orchestration.pipeline_task (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL,
  dag_id          UUID NOT NULL REFERENCES orchestration.dag(id) ON DELETE CASCADE,
  task_key        VARCHAR(128) NOT NULL,
  task_type       VARCHAR(32) NOT NULL,
  name            VARCHAR(256) NOT NULL,
  engine          VARCHAR(32) NOT NULL DEFAULT 'SPARK_SQL',
  target_fqn      VARCHAR(256),
  model_id        UUID,
  sync_task_id    UUID,
  config          JSONB NOT NULL DEFAULT '{}'::jsonb,
  compile_status  VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  compile_error   TEXT,
  executable      BOOLEAN NOT NULL DEFAULT false,
  position_x      INT,
  position_y      INT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (dag_id, task_key)
);
CREATE INDEX IF NOT EXISTS idx_pipeline_task_model
    ON orchestration.pipeline_task (model_id) WHERE model_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_pipeline_task_dag
    ON orchestration.pipeline_task (dag_id);
CREATE INDEX IF NOT EXISTS idx_pipeline_task_tenant
    ON orchestration.pipeline_task (tenant_id);

CREATE TABLE IF NOT EXISTS orchestration.pipeline_task_edge (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   UUID NOT NULL,
  dag_id      UUID NOT NULL REFERENCES orchestration.dag(id) ON DELETE CASCADE,
  source_key  VARCHAR(128) NOT NULL,
  target_key  VARCHAR(128) NOT NULL,
  edge_layer  VARCHAR(16) NOT NULL DEFAULT 'PIPELINE',
  source_port VARCHAR(32) DEFAULT 'out',
  target_port VARCHAR(32) DEFAULT 'in',
  source_output VARCHAR(64) DEFAULT 'out',
  target_input VARCHAR(64) DEFAULT 'in',
  asset_fqn   VARCHAR(256),
  input_alias VARCHAR(64),
  join_role   VARCHAR(32),
  trigger_policy VARCHAR(32) DEFAULT 'ALL_SUCCEEDED',
  freshness_policy VARCHAR(32) DEFAULT 'LATEST',
  auto        BOOLEAN NOT NULL DEFAULT false,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (dag_id, source_key, target_key, edge_layer)
);
CREATE INDEX IF NOT EXISTS idx_pipeline_task_edge_dag
    ON orchestration.pipeline_task_edge (dag_id);

CREATE TABLE IF NOT EXISTS orchestration.task_run (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id     UUID NOT NULL,
  job_run_id    UUID NOT NULL REFERENCES orchestration.job_run(id) ON DELETE CASCADE,
  task_key      VARCHAR(128) NOT NULL,
  status        VARCHAR(16) NOT NULL DEFAULT 'QUEUED',
  rows_written  BIGINT,
  scan_bytes    BIGINT,
  error_msg     TEXT,
  artifact_path VARCHAR(512),
  started_at    TIMESTAMPTZ,
  finished_at   TIMESTAMPTZ,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_task_run_job
    ON orchestration.task_run (job_run_id);
CREATE INDEX IF NOT EXISTS idx_task_run_tenant_status
    ON orchestration.task_run (tenant_id, status);
