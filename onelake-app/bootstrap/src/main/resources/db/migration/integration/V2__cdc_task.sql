-- CDC 实时采集任务表
CREATE TABLE IF NOT EXISTS integration.cdc_task (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID NOT NULL,
    source_id    UUID NOT NULL,
    source_name  VARCHAR(200) NOT NULL,
    table_name   VARCHAR(500) NOT NULL,
    topic_name   VARCHAR(500) NOT NULL,
    checkpoint   TEXT,
    status       VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    started_at   TIMESTAMP WITH TIME ZONE,
    created_at   TIMESTAMP WITH TIME ZONE DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_cdc_tenant ON integration.cdc_task (tenant_id);
