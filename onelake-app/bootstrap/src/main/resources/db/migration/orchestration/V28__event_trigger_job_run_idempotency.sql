-- M3-E1：资产事件运行级幂等
-- 回执写入失败并重试时，复用已经创建的 EVENT JobRun，避免再次启动下游流水线。

ALTER TABLE orchestration.job_run
  ADD COLUMN IF NOT EXISTS event_trigger_key VARCHAR(64);

CREATE UNIQUE INDEX IF NOT EXISTS uq_job_run_event_trigger_key
  ON orchestration.job_run (dag_id, event_trigger_key)
  WHERE trigger_type = 'EVENT' AND event_trigger_key IS NOT NULL;
