ALTER TABLE integration.sync_task
  ADD COLUMN IF NOT EXISTS source_table VARCHAR(256);

UPDATE integration.sync_task
SET source_table = target_table
WHERE source_table IS NULL;

ALTER TABLE integration.sync_task
  ALTER COLUMN source_table SET NOT NULL;
