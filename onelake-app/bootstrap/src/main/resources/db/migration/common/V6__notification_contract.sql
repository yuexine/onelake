ALTER TABLE common.notification
  ADD COLUMN IF NOT EXISTS content TEXT,
  ADD COLUMN IF NOT EXISTS level VARCHAR(16) NOT NULL DEFAULT 'INFO',
  ADD COLUMN IF NOT EXISTS source_ref_type VARCHAR(64),
  ADD COLUMN IF NOT EXISTS source_ref_id VARCHAR(128);

CREATE INDEX IF NOT EXISTS idx_notification_receiver_read
  ON common.notification (tenant_id, receiver_id, is_read, created_at DESC);

CREATE UNIQUE INDEX IF NOT EXISTS uk_notification_receiver_source
  ON common.notification (tenant_id, receiver_id, source_ref_type, source_ref_id)
  WHERE source_ref_type IS NOT NULL AND source_ref_id IS NOT NULL;
