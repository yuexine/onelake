DELETE FROM orchestration.compute_profile cp
USING orchestration.resource_group rg
WHERE cp.resource_group_id = rg.id
  AND rg.tenant_id IS NULL
  AND rg.code IN ('default', 'rg-default', 'python-default');

DELETE FROM orchestration.resource_group rg
WHERE rg.tenant_id IS NULL
  AND rg.code IN ('default', 'rg-default', 'python-default');

UPDATE orchestration.resource_group
SET display_name = 'Spark 默认资源组',
    engine = 'SPARK',
    status = 'ACTIVE',
    cost_policy = '{"runtime":"spark"}'::jsonb,
    updated_at = now()
WHERE tenant_id IS NULL
  AND code = 'spark-default';

INSERT INTO orchestration.resource_group
  (tenant_id, code, display_name, engine, status, max_concurrency, quota_cpu, quota_memory_gb, cost_policy)
VALUES
  (NULL, 'spark-default', 'Spark 默认资源组', 'SPARK', 'ACTIVE', 4, 96, 384,
    '{"runtime":"spark"}'::jsonb)
ON CONFLICT DO NOTHING;
