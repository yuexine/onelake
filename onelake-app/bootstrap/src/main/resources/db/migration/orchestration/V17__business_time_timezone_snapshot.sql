-- C1：冻结运行与回填创建时的业务时区，避免 DAG 配置变更重解释历史数据。

ALTER TABLE orchestration.job_run
  ADD COLUMN IF NOT EXISTS timezone VARCHAR(64);

UPDATE orchestration.job_run jr
SET timezone = COALESCE(NULLIF(TRIM(d.timezone), ''), 'Asia/Shanghai')
FROM orchestration.dag d
WHERE jr.dag_id = d.id
  AND jr.timezone IS NULL;

UPDATE orchestration.job_run
SET timezone = 'Asia/Shanghai'
WHERE timezone IS NULL;

ALTER TABLE orchestration.job_run
  ALTER COLUMN timezone SET DEFAULT 'Asia/Shanghai',
  ALTER COLUMN timezone SET NOT NULL;

ALTER TABLE orchestration.backfill
  ADD COLUMN IF NOT EXISTS timezone VARCHAR(64);

UPDATE orchestration.backfill b
SET timezone = COALESCE(NULLIF(TRIM(d.timezone), ''), 'Asia/Shanghai')
FROM orchestration.dag d
WHERE b.dag_id = d.id
  AND b.timezone IS NULL;

UPDATE orchestration.backfill
SET timezone = 'Asia/Shanghai'
WHERE timezone IS NULL;

ALTER TABLE orchestration.backfill
  ALTER COLUMN timezone SET DEFAULT 'Asia/Shanghai',
  ALTER COLUMN timezone SET NOT NULL;
