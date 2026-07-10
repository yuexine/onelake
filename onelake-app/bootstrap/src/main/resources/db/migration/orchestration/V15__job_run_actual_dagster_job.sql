-- M1 可观测性：保存每次运行实际 launch 的 Dagster job，GRAPH 模式不再误展示旧通用 job。
ALTER TABLE orchestration.job_run
  ADD COLUMN IF NOT EXISTS dagster_job VARCHAR(256);
