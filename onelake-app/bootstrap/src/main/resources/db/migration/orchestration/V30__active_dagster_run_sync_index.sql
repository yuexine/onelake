-- 后台每 30 秒按“最久未刷新优先”领取一页活跃 Dagster 运行。
-- 固定部分谓词避免历史终态进入索引，表达式顺序与 JobRunRepository 查询保持一致。
CREATE INDEX IF NOT EXISTS idx_job_run_active_dagster_sync
  ON orchestration.job_run (COALESCE(updated_at, started_at), id)
  WHERE status IN ('QUEUED', 'RUNNING')
    AND dagster_run_id IS NOT NULL;
