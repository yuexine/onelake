-- M1-D：分区定义 + 真回填批次
-- 关联 §4.4（D1/D2）

ALTER TABLE orchestration.pipeline_task
  ADD COLUMN IF NOT EXISTS partition_key     VARCHAR(64),   -- 分区列，如 dt
  ADD COLUMN IF NOT EXISTS partition_grain   VARCHAR(16);   -- DAY | HOUR | MONTH

ALTER TABLE orchestration.dag
  ADD COLUMN IF NOT EXISTS partition_grain   VARCHAR(16) DEFAULT 'DAY';

CREATE TABLE IF NOT EXISTS orchestration.backfill (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id     UUID NOT NULL,
  dag_id        UUID NOT NULL REFERENCES orchestration.dag(id) ON DELETE CASCADE,
  range_start   TIMESTAMPTZ NOT NULL,
  range_end     TIMESTAMPTZ NOT NULL,
  grain         VARCHAR(16) NOT NULL DEFAULT 'DAY',
  status        VARCHAR(16) NOT NULL DEFAULT 'QUEUED', -- QUEUED|RUNNING|SUCCEEDED|FAILED|CANCELLED|PARTIAL
  total_runs    INT NOT NULL DEFAULT 0,
  succeeded_runs INT NOT NULL DEFAULT 0,
  failed_runs   INT NOT NULL DEFAULT 0,
  max_parallel  INT NOT NULL DEFAULT 1,
  created_by     UUID,
  created_by_name VARCHAR(128),
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_backfill_dag ON orchestration.backfill (dag_id);
CREATE INDEX IF NOT EXISTS idx_backfill_tenant_status ON orchestration.backfill (tenant_id, status);
