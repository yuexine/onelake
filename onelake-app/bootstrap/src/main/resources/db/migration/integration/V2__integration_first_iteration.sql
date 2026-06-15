-- First integration backend iteration: query support and task name uniqueness.

ALTER TABLE integration.sync_task
  ADD CONSTRAINT uk_sync_task_tenant_name UNIQUE (tenant_id, name);

CREATE INDEX IF NOT EXISTS idx_synctask_tenant_status
  ON integration.sync_task (tenant_id, status);

CREATE INDEX IF NOT EXISTS idx_synctask_tenant_mode
  ON integration.sync_task (tenant_id, mode);

CREATE INDEX IF NOT EXISTS idx_syncrun_status_time
  ON integration.sync_run (status, started_at DESC);
