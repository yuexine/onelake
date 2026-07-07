-- 文件采集源
CREATE TABLE IF NOT EXISTS integration.file_source (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL,
    name        VARCHAR(200) NOT NULL,
    source_type VARCHAR(16) NOT NULL,
    endpoint    VARCHAR(500) NOT NULL,
    base_path   VARCHAR(1000),
    watch_mode  VARCHAR(8) DEFAULT 'event',
    enabled     BOOLEAN DEFAULT true,
    created_at  TIMESTAMP WITH TIME ZONE DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_file_src_tenant ON integration.file_source (tenant_id);
