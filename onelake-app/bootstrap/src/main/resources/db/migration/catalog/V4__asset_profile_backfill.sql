WITH latest_sync AS (
  SELECT DISTINCT ON (st.tenant_id, st.target_table)
    st.tenant_id,
    st.target_table,
    sr.rows_written,
    sr.finished_at
  FROM integration.sync_task st
  JOIN integration.sync_run sr ON sr.task_id = st.id
  WHERE sr.status = 'SUCCEEDED'
  ORDER BY st.tenant_id, st.target_table, sr.finished_at DESC NULLS LAST, sr.started_at DESC
)
UPDATE catalog.asset asset
SET
  row_count = COALESCE(asset.row_count, latest_sync.rows_written),
  last_sync_at = COALESCE(asset.last_sync_at, latest_sync.finished_at, asset.synced_at),
  format = COALESCE(asset.format, 'ICEBERG')
FROM latest_sync
WHERE asset.tenant_id = latest_sync.tenant_id
  AND asset.om_fqn = latest_sync.target_table;

DO $$
BEGIN
  IF to_regclass('quality.rule') IS NOT NULL
     AND to_regclass('quality.run_result') IS NOT NULL THEN
    EXECUTE $sql$
      WITH latest_quality AS (
        SELECT DISTINCT ON (rule.tenant_id, rule.target_fqn)
          rule.tenant_id,
          rule.target_fqn,
          result.pass_rate
        FROM quality.rule rule
        JOIN quality.run_result result ON result.rule_id = rule.id
        ORDER BY rule.tenant_id, rule.target_fqn, result.checked_at DESC
      )
      UPDATE catalog.asset asset
      SET quality_score = latest_quality.pass_rate
      FROM latest_quality
      WHERE asset.tenant_id = latest_quality.tenant_id
        AND asset.om_fqn = latest_quality.target_fqn
    $sql$;
  END IF;
END $$;

DO $$
BEGIN
  IF to_regclass('dataservice.api_definition') IS NOT NULL
     AND to_regclass('dataservice.subscription') IS NOT NULL THEN
    EXECUTE $sql$
      WITH api_usage AS (
        SELECT
          api.tenant_id,
          api.source_fqn,
          COUNT(sub.id) FILTER (WHERE sub.status = 'APPROVED')::int AS subscriptions
        FROM dataservice.api_definition api
        LEFT JOIN dataservice.subscription sub ON sub.api_id = api.id
        WHERE api.source_fqn IS NOT NULL AND api.source_fqn <> ''
        GROUP BY api.tenant_id, api.source_fqn
      )
      UPDATE catalog.asset asset
      SET popularity = COALESCE(api_usage.subscriptions, 0)
      FROM api_usage
      WHERE asset.tenant_id = api_usage.tenant_id
        AND asset.om_fqn = api_usage.source_fqn
    $sql$;
  END IF;
END $$;

UPDATE catalog.asset
SET
  format = COALESCE(format, 'ICEBERG'),
  partitions = COALESCE(partitions, '[]'::jsonb),
  popularity = COALESCE(popularity, 0),
  access_count = COALESCE(access_count, 0),
  last_sync_at = COALESCE(last_sync_at, synced_at)
WHERE asset_type = 'TABLE';
