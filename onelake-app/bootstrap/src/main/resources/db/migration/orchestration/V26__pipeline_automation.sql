-- M3-E：资产订阅 + 新鲜度就绪持久化
-- 关联 docs/数据开发与编排模块V2升级计划.md §4.5（E1/E2/E3）

CREATE TABLE IF NOT EXISTS orchestration.pipeline_subscription (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id        UUID NOT NULL,
  dag_id           UUID NOT NULL REFERENCES orchestration.dag(id) ON DELETE CASCADE,
  source_type      VARCHAR(16) NOT NULL,
  source_ref       VARCHAR(256) NOT NULL,
  condition        VARCHAR(32) NOT NULL DEFAULT 'ON_UPDATE',
  freshness_policy VARCHAR(32) NOT NULL DEFAULT 'LATEST',
  enabled          BOOLEAN NOT NULL DEFAULT true,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_pipeline_subscription UNIQUE (dag_id, source_type, source_ref)
);

CREATE INDEX IF NOT EXISTS idx_subscription_source
  ON orchestration.pipeline_subscription (tenant_id, source_type, source_ref);

CREATE TABLE IF NOT EXISTS orchestration.asset_readiness (
  tenant_id       UUID NOT NULL,
  dag_id          UUID NOT NULL,
  task_key        VARCHAR(128) NOT NULL,
  asset_fqn       VARCHAR(256) NOT NULL,
  batch_id        VARCHAR(128),
  freshness_window VARCHAR(64),
  ready_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (dag_id, task_key)
);
