-- First integration backend iteration: query support and task name uniqueness.

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint
    WHERE conname = 'uk_sync_task_tenant_name'
      AND conrelid = 'integration.sync_task'::regclass
  ) THEN
    ALTER TABLE integration.sync_task
      ADD CONSTRAINT uk_sync_task_tenant_name UNIQUE (tenant_id, name);
  END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_synctask_tenant_status
  ON integration.sync_task (tenant_id, status);

CREATE INDEX IF NOT EXISTS idx_synctask_tenant_mode
  ON integration.sync_task (tenant_id, mode);

CREATE INDEX IF NOT EXISTS idx_syncrun_status_time
  ON integration.sync_run (status, started_at DESC);
