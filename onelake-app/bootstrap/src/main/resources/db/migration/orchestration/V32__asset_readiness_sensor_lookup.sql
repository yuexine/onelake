-- M4 SENSOR 高频轮询：按租户、资产及可选分区只读取最新就绪信号。

CREATE INDEX IF NOT EXISTS idx_asset_readiness_sensor_latest
  ON orchestration.asset_readiness (tenant_id, asset_fqn, ready_at DESC);

CREATE INDEX IF NOT EXISTS idx_asset_readiness_sensor_partition
  ON orchestration.asset_readiness (tenant_id, asset_fqn, batch_id, ready_at DESC);
