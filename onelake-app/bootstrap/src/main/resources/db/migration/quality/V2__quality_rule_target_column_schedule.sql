ALTER TABLE quality.rule
  ADD COLUMN IF NOT EXISTS target_column VARCHAR(128);

ALTER TABLE quality.rule
  ADD COLUMN IF NOT EXISTS schedule VARCHAR(32) NOT NULL DEFAULT 'ON_PARTITION';

CREATE INDEX IF NOT EXISTS idx_quality_rule_target
  ON quality.rule (tenant_id, target_fqn, enabled);
