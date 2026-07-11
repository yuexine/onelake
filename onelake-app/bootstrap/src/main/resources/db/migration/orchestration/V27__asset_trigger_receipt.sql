-- M3-E1：资产事件触发回执
-- 同一 DAG、来源资产和业务窗口只允许处理一次，补足 scheduler_lock 释放后的持久化幂等。

CREATE TABLE IF NOT EXISTS orchestration.asset_trigger_receipt (
  tenant_id           UUID NOT NULL,
  dag_id              UUID NOT NULL REFERENCES orchestration.dag(id) ON DELETE CASCADE,
  trigger_key         VARCHAR(64) NOT NULL,
  event_id            UUID,
  source_type         VARCHAR(16) NOT NULL,
  source_ref          VARCHAR(512) NOT NULL,
  batch_id            VARCHAR(128),
  logical_date        TIMESTAMPTZ,
  pipeline_version_id UUID REFERENCES orchestration.pipeline_version(id) ON DELETE SET NULL,
  job_run_id          UUID REFERENCES orchestration.job_run(id) ON DELETE SET NULL,
  status              VARCHAR(16) NOT NULL,
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (dag_id, trigger_key)
);

CREATE INDEX IF NOT EXISTS idx_asset_trigger_receipt_tenant_time
  ON orchestration.asset_trigger_receipt (tenant_id, created_at DESC);
