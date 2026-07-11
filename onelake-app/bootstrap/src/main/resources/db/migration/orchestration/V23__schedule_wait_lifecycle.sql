-- M2 收口：misfire FIRE_ONCE 与依赖等待生命周期。

ALTER TABLE orchestration.dag
  ADD COLUMN IF NOT EXISTS dependency_wait_timeout_minutes INT NOT NULL DEFAULT 1440;

ALTER TABLE orchestration.pipeline_dependency_wait
  ADD COLUMN IF NOT EXISTS wait_reason   VARCHAR(16)   NOT NULL DEFAULT 'DEPENDENCY',
  ADD COLUMN IF NOT EXISTS status        VARCHAR(16)   NOT NULL DEFAULT 'WAITING',
  ADD COLUMN IF NOT EXISTS last_blockers VARCHAR(2000),
  ADD COLUMN IF NOT EXISTS expires_at    TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS resolved_at   TIMESTAMPTZ;

UPDATE orchestration.pipeline_dependency_wait wait_record
SET expires_at = wait_record.created_at + make_interval(
        mins => COALESCE((
          SELECT dag.dependency_wait_timeout_minutes
          FROM orchestration.dag dag
          WHERE dag.id = wait_record.dag_id
        ), 1440))
WHERE wait_record.expires_at IS NULL;

ALTER TABLE orchestration.pipeline_dependency_wait
  ALTER COLUMN expires_at SET NOT NULL;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conname = 'ck_dag_dependency_wait_timeout_positive'
      AND conrelid = 'orchestration.dag'::regclass
  ) THEN
    ALTER TABLE orchestration.dag
      ADD CONSTRAINT ck_dag_dependency_wait_timeout_positive
      CHECK (dependency_wait_timeout_minutes > 0);
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conname = 'ck_pipeline_dependency_wait_reason'
      AND conrelid = 'orchestration.pipeline_dependency_wait'::regclass
  ) THEN
    ALTER TABLE orchestration.pipeline_dependency_wait
      ADD CONSTRAINT ck_pipeline_dependency_wait_reason
      CHECK (wait_reason IN ('DEPENDENCY', 'MISFIRE'));
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conname = 'ck_pipeline_dependency_wait_status'
      AND conrelid = 'orchestration.pipeline_dependency_wait'::regclass
  ) THEN
    ALTER TABLE orchestration.pipeline_dependency_wait
      ADD CONSTRAINT ck_pipeline_dependency_wait_status
      CHECK (status IN ('WAITING', 'RESOLVED', 'TIMED_OUT', 'CANCELLED'));
  END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_pipeline_dependency_wait_status
  ON orchestration.pipeline_dependency_wait (status, created_at, id);
