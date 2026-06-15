CREATE SCHEMA IF NOT EXISTS quality;

CREATE TABLE IF NOT EXISTS quality.rule (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   UUID NOT NULL,
  target_fqn  VARCHAR(512) NOT NULL,
  rule_type   VARCHAR(32) NOT NULL,
  expression  TEXT NOT NULL,
  severity    VARCHAR(16) NOT NULL DEFAULT 'BLOCK',
  owner_id    UUID,
  enabled     BOOLEAN NOT NULL DEFAULT true,
  version     INT NOT NULL DEFAULT 1,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS quality.run_result (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  rule_id     UUID NOT NULL REFERENCES quality.rule(id),
  job_run_id  UUID,
  passed      BOOLEAN NOT NULL,
  pass_rate   NUMERIC(5,2),
  failed_rows BIGINT DEFAULT 0,
  sample      JSONB,
  checked_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_quality_rule_time ON quality.run_result (rule_id, checked_at DESC);

CREATE TABLE IF NOT EXISTS quality.score_snapshot (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   UUID NOT NULL,
  target_fqn  VARCHAR(512) NOT NULL,
  dimension   VARCHAR(16) NOT NULL,
  score       NUMERIC(5,2) NOT NULL,
  stat_date   DATE NOT NULL,
  UNIQUE (tenant_id, target_fqn, dimension, stat_date)
);

CREATE TABLE IF NOT EXISTS quality.alert (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   UUID NOT NULL,
  rule_id     UUID REFERENCES quality.rule(id),
  level       VARCHAR(16) NOT NULL,
  message     VARCHAR(512) NOT NULL,
  status      VARCHAR(16) NOT NULL DEFAULT 'OPEN',
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_alert_status ON quality.alert (tenant_id, status, created_at DESC);
