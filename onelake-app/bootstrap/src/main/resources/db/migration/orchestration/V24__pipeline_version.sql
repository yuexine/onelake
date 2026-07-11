-- M3-F：不可变版本快照 + run 绑定版本 + 开发/生产隔离
-- 关联 docs/数据开发与编排模块V2升级计划.md §4.6（F1/F4）、§5

CREATE TABLE IF NOT EXISTS orchestration.pipeline_version (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id         UUID NOT NULL,
  dag_id            UUID NOT NULL REFERENCES orchestration.dag(id) ON DELETE CASCADE,
  version           INT NOT NULL,
  snapshot          jsonb NOT NULL,
  checksum          VARCHAR(64),
  status            VARCHAR(16) NOT NULL DEFAULT 'PUBLISHED',
  note              VARCHAR(512),
  published_by      UUID,
  published_by_name VARCHAR(128),
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_pipeline_version UNIQUE (dag_id, version)
);

CREATE INDEX IF NOT EXISTS idx_pipeline_version_dag
  ON orchestration.pipeline_version (dag_id);

ALTER TABLE orchestration.job_run
  ADD COLUMN IF NOT EXISTS pipeline_version_id UUID;

ALTER TABLE orchestration.dag
  ADD COLUMN IF NOT EXISTS published_version_id UUID,
  ADD COLUMN IF NOT EXISTS has_unpublished_changes BOOLEAN NOT NULL DEFAULT false;

CREATE INDEX IF NOT EXISTS idx_job_run_version
  ON orchestration.job_run (pipeline_version_id)
  WHERE pipeline_version_id IS NOT NULL;
