ALTER TABLE orchestration.job_run
  ADD COLUMN IF NOT EXISTS triggered_by_name text;
