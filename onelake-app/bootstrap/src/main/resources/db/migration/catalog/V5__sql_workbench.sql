CREATE TABLE IF NOT EXISTS catalog.sql_query_history (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL,
  user_id         UUID,
  runner          VARCHAR(128),
  sql_text        TEXT NOT NULL,
  engine          VARCHAR(32) NOT NULL DEFAULT 'TRINO',
  resource_group  VARCHAR(64),
  status          VARCHAR(16) NOT NULL,
  duration_ms     BIGINT,
  scan_bytes      BIGINT,
  row_count       BIGINT,
  error_code      VARCHAR(64),
  error_message   TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_sql_query_history_tenant_time
  ON catalog.sql_query_history (tenant_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_sql_query_history_tenant_status
  ON catalog.sql_query_history (tenant_id, status, created_at DESC);

CREATE TABLE IF NOT EXISTS catalog.saved_query (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   UUID NOT NULL,
  owner_id    UUID,
  owner_name  VARCHAR(128),
  name        VARCHAR(128) NOT NULL,
  sql_text    TEXT NOT NULL,
  shared      BOOLEAN NOT NULL DEFAULT false,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, name)
);
CREATE INDEX IF NOT EXISTS idx_saved_query_tenant_updated
  ON catalog.saved_query (tenant_id, updated_at DESC);
