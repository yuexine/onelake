-- Outbox -> Redis Stream global event contract support.
ALTER TABLE common.outbox_event
  ADD COLUMN IF NOT EXISTS tenant_id UUID,
  ADD COLUMN IF NOT EXISTS aggregate_type VARCHAR(64),
  ADD COLUMN IF NOT EXISTS retry_count INT NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS published_at TIMESTAMPTZ;

UPDATE common.outbox_event
SET status = 'PUBLISHED'
WHERE status = 'SENT';

UPDATE common.outbox_event
SET status = 'DEAD'
WHERE status = 'FAILED';

UPDATE common.outbox_event
SET aggregate_type = split_part(event_type, '.', 1)
WHERE aggregate_type IS NULL;

CREATE INDEX IF NOT EXISTS idx_outbox_pending
  ON common.outbox_event (status, occurred_at)
  WHERE status = 'PENDING';

CREATE TABLE IF NOT EXISTS common.consumed_event (
  event_id    UUID NOT NULL,
  consumer    VARCHAR(64) NOT NULL,
  consumed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (event_id, consumer)
);
