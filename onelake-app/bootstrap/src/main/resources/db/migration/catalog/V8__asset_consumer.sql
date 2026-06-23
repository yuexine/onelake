-- 血缘图模块：影响分析投影表（对应《血缘图模块完善设计方案》§5.1.1）
-- catalog 模块不得跨 schema 直读 dataservice/orchestration，改为订阅事件后投影到自有表。

CREATE TABLE IF NOT EXISTS catalog.asset_consumer (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL,
    asset_fqn     VARCHAR(512) NOT NULL,
    consumer_type VARCHAR(16) NOT NULL,
    consumer_ref  VARCHAR(256) NOT NULL,
    consumer_name VARCHAR(256),
    owner_name    VARCHAR(128),
    status        VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    synced_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, asset_fqn, consumer_type, consumer_ref)
);

-- 影响分析查询模式：WHERE tenant_id=? AND asset_fqn IN (...) AND status='ACTIVE'
CREATE INDEX IF NOT EXISTS idx_asset_consumer_fqn
    ON catalog.asset_consumer (tenant_id, asset_fqn)
    WHERE status = 'ACTIVE';
