-- 运营告警表（跨模块通用，任何模块都可通过 Outbox 事件触发告警）
CREATE TABLE IF NOT EXISTS common.alert (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL,
    level         VARCHAR(4) NOT NULL,             -- P0 / P1 / P2
    source        VARCHAR(32) NOT NULL,            -- 采集 / DaaS / 质量 / 湖仓 / 安全
    title         VARCHAR(500) NOT NULL,
    rule          VARCHAR(200),                    -- 触发规则描述
    status        VARCHAR(16) NOT NULL DEFAULT 'OPEN', -- OPEN / PROCESSING / CLOSED
    assignee      VARCHAR(64),
    related_run_id  UUID,
    related_api     VARCHAR(200),
    created_at    TIMESTAMPTZ DEFAULT now(),
    acked_at      TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_alert_tenant_status ON common.alert (tenant_id, status);
CREATE INDEX IF NOT EXISTS idx_alert_level ON common.alert (level, status);
