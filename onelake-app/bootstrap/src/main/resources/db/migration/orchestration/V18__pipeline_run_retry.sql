-- C4：流水线空跑/冻结配套的 DAG 级失败自动重跑策略。
-- run_retry_count 表示首次运行失败后最多再创建多少条 AUTO_RETRY JobRun。

ALTER TABLE orchestration.dag
  ADD COLUMN IF NOT EXISTS run_retry_count            INT NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS run_retry_interval_seconds INT NOT NULL DEFAULT 0;

ALTER TABLE orchestration.job_run
  ADD COLUMN IF NOT EXISTS retry_source_run_id UUID,
  ADD COLUMN IF NOT EXISTS run_retry_attempt   INT NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS retry_dispatched_at TIMESTAMPTZ;

ALTER TABLE orchestration.dag
  ADD CONSTRAINT chk_dag_run_retry_count
    CHECK (run_retry_count >= 0),
  ADD CONSTRAINT chk_dag_run_retry_interval
    CHECK (run_retry_interval_seconds >= 0);

ALTER TABLE orchestration.job_run
  ADD CONSTRAINT chk_job_run_retry_attempt
    CHECK (run_retry_attempt >= 0);

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint
    WHERE conname = 'fk_job_run_retry_source'
      AND conrelid = 'orchestration.job_run'::regclass
  ) THEN
    ALTER TABLE orchestration.job_run
      ADD CONSTRAINT fk_job_run_retry_source
      FOREIGN KEY (retry_source_run_id)
      REFERENCES orchestration.job_run(id)
      ON DELETE SET NULL;
  END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_job_run_retry_pending
  ON orchestration.job_run (finished_at, id)
  WHERE status = 'FAILED' AND retry_dispatched_at IS NULL;

-- 每条失败来源最多创建一个直接重跑，兼顾重跑链查询和多实例幂等兜底。
CREATE UNIQUE INDEX IF NOT EXISTS uq_job_run_retry_source
  ON orchestration.job_run (retry_source_run_id)
  WHERE retry_source_run_id IS NOT NULL;

-- 一个回填周期仍只允许一条初始 BACKFILL JobRun，但允许其后生成 AUTO_RETRY 链。
DROP INDEX IF EXISTS orchestration.uq_job_run_backfill_logical_date;
CREATE UNIQUE INDEX uq_job_run_backfill_logical_date
  ON orchestration.job_run (backfill_id, logical_date)
  WHERE backfill_id IS NOT NULL
    AND logical_date IS NOT NULL
    AND trigger_type = 'BACKFILL';
