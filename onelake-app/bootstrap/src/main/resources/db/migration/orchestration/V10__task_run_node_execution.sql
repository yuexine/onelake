-- M1-A：节点级执行与数据日期字段
-- 关联 docs/数据开发与编排模块V2升级计划.md §5、§4.1、§4.3

ALTER TABLE orchestration.task_run
  ADD COLUMN IF NOT EXISTS attempt             INT NOT NULL DEFAULT 1,
  ADD COLUMN IF NOT EXISTS max_retries         INT NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS log_ref             VARCHAR(512),
  ADD COLUMN IF NOT EXISTS dagster_step_key    VARCHAR(256),
  ADD COLUMN IF NOT EXISTS data_interval_start TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS data_interval_end   TIMESTAMPTZ;

ALTER TABLE orchestration.job_run
  ADD COLUMN IF NOT EXISTS logical_date        TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS data_interval_start TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS data_interval_end   TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS backfill_id         UUID,
  ADD COLUMN IF NOT EXISTS updated_at          TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE INDEX IF NOT EXISTS idx_job_run_backfill
    ON orchestration.job_run (backfill_id) WHERE backfill_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_task_run_step
    ON orchestration.task_run (dagster_step_key) WHERE dagster_step_key IS NOT NULL;
