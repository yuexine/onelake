-- M1-C6：调度触发幂等 + 分布式锁
-- 关联 docs/数据开发与编排模块V2升级计划.md §9 R2
-- V13 已用于 backfill_run 派发队列；本迁移沿用原 V13 草案的调度约束内容。

-- 同一 DAG 同一 logical_date 的 CRON 触发只允许一条 job_run。
CREATE UNIQUE INDEX IF NOT EXISTS uq_job_run_cron_logical
    ON orchestration.job_run (dag_id, logical_date)
    WHERE trigger_type = 'CRON' AND logical_date IS NOT NULL;

-- 调度器多副本互斥锁。expires_at 允许后续实例接管故障实例遗留的锁。
CREATE TABLE IF NOT EXISTS orchestration.scheduler_lock (
  lock_key    VARCHAR(64) PRIMARY KEY,
  holder      VARCHAR(128) NOT NULL,
  acquired_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at  TIMESTAMPTZ NOT NULL
);
