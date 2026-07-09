-- 节点级 task_run 回调字段。
-- V10 已经为节点执行引入过这些列；本迁移用于兼容补齐，并把 dagster_step_key 收窄到回调契约约定的长度。

ALTER TABLE orchestration.task_run
  ADD COLUMN IF NOT EXISTS attempt          INT NOT NULL DEFAULT 1,
  ADD COLUMN IF NOT EXISTS log_ref          VARCHAR(512),
  ADD COLUMN IF NOT EXISTS dagster_step_key VARCHAR(128);

ALTER TABLE orchestration.task_run
  ALTER COLUMN attempt SET DEFAULT 1,
  ALTER COLUMN attempt SET NOT NULL,
  ALTER COLUMN log_ref TYPE VARCHAR(512),
  ALTER COLUMN dagster_step_key TYPE VARCHAR(128) USING left(dagster_step_key, 128);
