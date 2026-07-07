-- ============================================================
-- integration 模块 demo 种子数据
-- 匹配前端 mock（src/mock/l1-integration.ts），使前端接入真实 API 后页面不空
-- ============================================================

-- ---------- 数据源 ----------
INSERT INTO integration.datasource (id, tenant_id, project_id, name, type, config, network_mode, env_level, health, last_check_at)
VALUES
  ('dddddddd-0001-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1', '订单库', 'MYSQL',
   '{"host":"10.0.0.1","port":3306,"dbName":"order_db","username":"ro_user"}'::jsonb,
   'DIRECT','PROD','OK','2026-06-14T02:00:01Z'),
  ('dddddddd-0002-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1', '用户库', 'POSTGRES',
   '{"host":"10.0.0.2","port":5432,"dbName":"user_db","username":"ro_user"}'::jsonb,
   'DIRECT','PROD','FAIL','2026-06-14T02:05:01Z'),
  ('dddddddd-0003-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2', '日志-S3', 'S3',
   '{"host":"s3.cn-north-1.amazonaws.com","port":443,"dbName":"access-log","username":"ak_xxx"}'::jsonb,
   'VPC','PROD','OK','2026-06-14T01:50:00Z'),
  ('dddddddd-0004-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa3', '风控-Hive', 'HIVE',
   '{"host":"hive.internal","port":10000,"dbName":"risk","username":"risk_ro"}'::jsonb,
   'DIRECT','PROD','OK','2026-06-14T02:00:00Z'),
  ('dddddddd-0005-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2', '消息-Kafka', 'KAFKA',
   '{"host":"kafka-broker-1","port":9092,"dbName":"events","username":"consumer"}'::jsonb,
   'VPC','PROD','OK','2026-06-14T02:10:00Z')
ON CONFLICT DO NOTHING;

-- ---------- 采集任务 ----------
ALTER TABLE integration.sync_task
  ADD COLUMN IF NOT EXISTS source_table VARCHAR(256);

INSERT INTO integration.sync_task (id, tenant_id, source_id, name, mode, target_table, source_table, schedule_cron, rate_limit, status, airbyte_connection_id)
VALUES
  ('55555555-0001-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'dddddddd-0001-0000-0000-000000000001', 'orders_sync', 'INCREMENTAL', 'ods.orders', 'orders', '0 2 * * *', 2500, 'ENABLED', 'abc-001'),
  ('55555555-0002-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'dddddddd-0001-0000-0000-000000000001', 'order_items_sync', 'INCREMENTAL', 'ods.order_items', 'order_items', '0 2 * * *', 2500, 'ENABLED', NULL),
  ('55555555-0003-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'dddddddd-0002-0000-0000-000000000001', 'user_cdc', 'CDC', 'ods.users', 'users', '', NULL, 'ENABLED', NULL),
  ('55555555-0004-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'dddddddd-0003-0000-0000-000000000001', 'logs_file', 'FILE', 'ods.access_log', 'access_log', '', NULL, 'ENABLED', NULL),
  ('55555555-0005-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'dddddddd-0004-0000-0000-000000000001', 'risk_events', 'FULL', 'ods.risk_events', 'risk_events', '0 4 * * 1', NULL, 'DRAFT', NULL)
ON CONFLICT (tenant_id, name) DO UPDATE SET
  source_id = EXCLUDED.source_id,
  mode = EXCLUDED.mode,
  target_table = EXCLUDED.target_table,
  source_table = EXCLUDED.source_table,
  schedule_cron = EXCLUDED.schedule_cron,
  rate_limit = EXCLUDED.rate_limit,
  status = EXCLUDED.status,
  airbyte_connection_id = EXCLUDED.airbyte_connection_id;

-- ---------- 运行实例 ----------
INSERT INTO integration.sync_run (id, task_id, external_job_id, status, rows_read, rows_written, started_at, finished_at, error_code, error_msg)
SELECT v.id, st.id, v.external_job_id, v.status, v.rows_read, v.rows_written, v.started_at, v.finished_at, v.error_code, v.error_msg
FROM (
  VALUES
    ('66666666-1042-0000-0000-000000000001'::uuid, 'orders_sync', 'ab-1042', 'SUCCEEDED', 123456::bigint, 123456::bigint, '2026-06-14T02:00:01Z'::timestamptz, '2026-06-14T02:00:49Z'::timestamptz, NULL, NULL),
    ('66666666-1041-0000-0000-000000000001'::uuid, 'orders_sync', 'ab-1041', 'FAILED', 0::bigint, 0::bigint, '2026-06-13T02:00:01Z'::timestamptz, '2026-06-13T02:00:13Z'::timestamptz, 'AUTH_401', '账号密码过期'),
    ('66666666-1040-0000-0000-000000000001'::uuid, 'orders_sync', 'ab-1040', 'SUCCEEDED', 122800::bigint, 122800::bigint, '2026-06-12T02:00:01Z'::timestamptz, '2026-06-12T02:00:45Z'::timestamptz, NULL, NULL),
    ('66666666-1038-0000-0000-000000000001'::uuid, 'order_items_sync', 'ab-1038', 'SUCCEEDED', 89500::bigint, 89500::bigint, '2026-06-14T02:00:01Z'::timestamptz, '2026-06-14T02:00:30Z'::timestamptz, NULL, NULL),
    ('66666666-1037-0000-0000-000000000001'::uuid, 'user_cdc', 'flink-9381', 'RUNNING', 1843201::bigint, 1843201::bigint, '2026-06-13T08:00:00Z'::timestamptz, NULL, NULL, NULL)
) AS v(id, task_name, external_job_id, status, rows_read, rows_written, started_at, finished_at, error_code, error_msg)
JOIN integration.sync_task st
  ON st.tenant_id = '11111111-1111-1111-1111-111111111111'
 AND st.name = v.task_name
ON CONFLICT DO NOTHING;

-- ---------- CDC 任务 ----------
INSERT INTO integration.cdc_task (id, tenant_id, source_id, source_name, table_name, topic_name, checkpoint, status, started_at)
VALUES
  ('cccccccc-0001-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'dddddddd-0002-0000-0000-000000000001', '用户库', 'ods.users', 'onelake.cdc.users', 'binlog.000128:4456', 'RUNNING', '2026-06-13T08:00:00Z')
ON CONFLICT DO NOTHING;

-- ---------- 文件采集源 ----------
INSERT INTO integration.file_source (id, tenant_id, name, source_type, endpoint, base_path, watch_mode, enabled)
VALUES
  ('ffffffff-0001-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'S3-订单文件', 'S3', 's3.cn-north-1.amazonaws.com', '/data/inbound/orders/', 'event', true)
ON CONFLICT DO NOTHING;
