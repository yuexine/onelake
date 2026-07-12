-- M3-E3：资产更新与质量结果的持久化配对状态
-- 按 tenant + asset 维护最新更新、最新质量结果及其配对关系，支持乱序事件和多副本消费。

ALTER TABLE orchestration.pipeline_subscription
  ALTER COLUMN source_ref TYPE VARCHAR(512);

ALTER TABLE orchestration.asset_readiness
  ALTER COLUMN asset_fqn TYPE VARCHAR(512);

CREATE TABLE IF NOT EXISTS orchestration.asset_quality_state (
  tenant_id                       UUID NOT NULL,
  asset_fqn                       VARCHAR(512) NOT NULL,
  update_event_id                 UUID,
  update_event_type               VARCHAR(64),
  update_batch_id                 VARCHAR(128),
  update_run_id                   VARCHAR(128),
  update_logical_date             TIMESTAMPTZ,
  update_freshness_window         VARCHAR(64),
  update_pipeline_id              VARCHAR(128),
  update_occurred_at              TIMESTAMPTZ,
  quality_event_id                UUID,
  quality_passed                  BOOLEAN,
  quality_correlation_key         VARCHAR(512),
  quality_applied_update_event_id UUID,
  quality_checked_at              TIMESTAMPTZ,
  updated_at                      TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, asset_fqn)
);
