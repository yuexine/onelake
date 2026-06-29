CREATE TABLE IF NOT EXISTS orchestration.resource_group (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id        UUID,
  code             VARCHAR(64) NOT NULL,
  display_name     VARCHAR(128) NOT NULL,
  engine           VARCHAR(32) NOT NULL,
  status           VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  max_concurrency  INT,
  quota_cpu        INT,
  quota_memory_gb  INT,
  cost_policy      JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_resource_group_builtin_code
  ON orchestration.resource_group (code)
  WHERE tenant_id IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_resource_group_tenant_code
  ON orchestration.resource_group (tenant_id, code)
  WHERE tenant_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_resource_group_tenant_engine
  ON orchestration.resource_group (tenant_id, engine, status);

CREATE TABLE IF NOT EXISTS orchestration.compute_profile (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  resource_group_id UUID NOT NULL REFERENCES orchestration.resource_group(id) ON DELETE CASCADE,
  code              VARCHAR(64) NOT NULL,
  display_name      VARCHAR(128) NOT NULL,
  engine            VARCHAR(32) NOT NULL,
  status            VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  cpu_cores         INT,
  memory_gb         INT,
  max_scan_bytes    BIGINT,
  timeout_seconds   INT,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (resource_group_id, code)
);

CREATE INDEX IF NOT EXISTS idx_compute_profile_group_status
  ON orchestration.compute_profile (resource_group_id, status);

INSERT INTO orchestration.resource_group
  (tenant_id, code, display_name, engine, status, max_concurrency, quota_cpu, quota_memory_gb, cost_policy)
VALUES
  (NULL, 'spark-default', 'Spark 默认资源组', 'SPARK', 'ACTIVE', 4, 96, 384,
    '{"runtime":"spark"}'::jsonb)
ON CONFLICT DO NOTHING;

INSERT INTO orchestration.compute_profile
  (resource_group_id, code, display_name, engine, status, cpu_cores, memory_gb, max_scan_bytes, timeout_seconds)
SELECT rg.id, profile.code, profile.display_name, rg.engine, 'ACTIVE',
       profile.cpu_cores, profile.memory_gb, profile.max_scan_bytes, profile.timeout_seconds
FROM orchestration.resource_group rg
JOIN (
  VALUES
    ('spark-default', 'spark-small', 'Spark Small', 4, 16, NULL::BIGINT, 3600),
    ('spark-default', 'spark-medium', 'Spark Medium', 8, 32, NULL::BIGINT, 7200),
    ('spark-default', 'spark-large', 'Spark Large', 16, 64, NULL::BIGINT, 14400)
) AS profile(group_code, code, display_name, cpu_cores, memory_gb, max_scan_bytes, timeout_seconds)
  ON rg.tenant_id IS NULL AND rg.code = profile.group_code
ON CONFLICT DO NOTHING;
