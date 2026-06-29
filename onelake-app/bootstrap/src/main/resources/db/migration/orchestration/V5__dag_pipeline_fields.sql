-- P1: dag 表扩字段（流水线 v2 属性）
-- C8 约束：job_run 状态枚举迁移由 common/V7 统一处理，本迁移不动 job_run.status

ALTER TABLE orchestration.dag
  ADD COLUMN IF NOT EXISTS pipeline_kind   VARCHAR(16) DEFAULT 'BLANK',
  ADD COLUMN IF NOT EXISTS status          VARCHAR(16) DEFAULT 'DRAFT',
  ADD COLUMN IF NOT EXISTS engine          VARCHAR(32) DEFAULT 'SPARK',
  ADD COLUMN IF NOT EXISTS resource_group  VARCHAR(64) DEFAULT 'spark-default',
  ADD COLUMN IF NOT EXISTS compute_profile VARCHAR(64) DEFAULT 'spark-small';

-- 流水线状态历史（发布工作流用，P4 落地实际状态机；P1 仅建表占位）
CREATE TABLE IF NOT EXISTS orchestration.pipeline_status_history (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id     UUID NOT NULL,
  dag_id        UUID NOT NULL REFERENCES orchestration.dag(id) ON DELETE CASCADE,
  from_status   VARCHAR(16),
  to_status     VARCHAR(16) NOT NULL,
  version       INT NOT NULL,
  changed_by    UUID,
  changed_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  reason        TEXT
);
CREATE INDEX IF NOT EXISTS idx_pipeline_status_history_dag
    ON orchestration.pipeline_status_history (dag_id, changed_at DESC);
