CREATE SCHEMA IF NOT EXISTS dataservice;
CREATE SCHEMA IF NOT EXISTS dataservice_api;

-- 允许 PostgREST 匿名角色访问 dataservice_api
DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'web_anon') THEN
    CREATE ROLE web_anon NOLOGIN;
  END IF;
  GRANT USAGE ON SCHEMA dataservice_api TO web_anon;
END $$;

CREATE TABLE IF NOT EXISTS dataservice.api_definition (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   UUID NOT NULL,
  api_path    VARCHAR(256) NOT NULL,
  view_name   VARCHAR(128) NOT NULL,
  select_sql  TEXT NOT NULL,
  source_fqn  VARCHAR(512),
  qps_limit   INT NOT NULL DEFAULT 20,
  status      VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  current_version INT NOT NULL DEFAULT 1,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, api_path)
);

CREATE TABLE IF NOT EXISTS dataservice.api_version (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  api_id      UUID NOT NULL REFERENCES dataservice.api_definition(id),
  version     INT NOT NULL,
  spec        JSONB NOT NULL,
  published_at TIMESTAMPTZ,
  deprecated_at TIMESTAMPTZ,
  UNIQUE (api_id, version)
);

CREATE TABLE IF NOT EXISTS dataservice.app_key (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   UUID NOT NULL,
  app_key     VARCHAR(64) NOT NULL UNIQUE,
  secret_hash VARCHAR(256) NOT NULL,
  owner_id    UUID,
  ip_whitelist JSONB,
  quota_daily BIGINT,
  expires_at  TIMESTAMPTZ,
  status      VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS dataservice.subscription (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  api_id      UUID NOT NULL REFERENCES dataservice.api_definition(id),
  subscriber_id UUID NOT NULL,
  app_key_id  UUID REFERENCES dataservice.app_key(id),
  status      VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  approved_by UUID,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS dataservice.api_call_log (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  api_id      UUID NOT NULL,
  app_key_id  UUID,
  status_code INT NOT NULL,
  latency_ms  INT,
  request_ip  VARCHAR(64),
  called_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_calllog_api_time ON dataservice.api_call_log (api_id, called_at DESC);

CREATE TABLE IF NOT EXISTS dataservice.quota_usage (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  app_key_id  UUID NOT NULL REFERENCES dataservice.app_key(id),
  api_id      UUID NOT NULL REFERENCES dataservice.api_definition(id),
  stat_date   DATE NOT NULL,
  call_count  BIGINT NOT NULL DEFAULT 0,
  UNIQUE (app_key_id, api_id, stat_date)
);
