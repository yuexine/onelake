CREATE TABLE IF NOT EXISTS common.running_task (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL,
  user_id         UUID,
  source_module   VARCHAR(32) NOT NULL,
  task_type       VARCHAR(32) NOT NULL,
  ref_type        VARCHAR(64) NOT NULL,
  ref_id          VARCHAR(128) NOT NULL,
  parent_ref_id   VARCHAR(128),
  title           VARCHAR(256) NOT NULL,
  status          VARCHAR(16) NOT NULL,
  progress        INT,
  phase           VARCHAR(64),
  detail          VARCHAR(512),
  error_code      VARCHAR(64),
  error_message   TEXT,
  link            VARCHAR(512),
  cancellable     BOOLEAN NOT NULL DEFAULT false,
  cancel_endpoint VARCHAR(512),
  started_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  finished_at     TIMESTAMPTZ,
  expires_at      TIMESTAMPTZ,
  dismissed_at    TIMESTAMPTZ,
  UNIQUE (tenant_id, ref_type, ref_id)
);

CREATE INDEX IF NOT EXISTS idx_running_task_tenant_status
  ON common.running_task (tenant_id, status, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_running_task_tenant_time
  ON common.running_task (tenant_id, updated_at DESC);
