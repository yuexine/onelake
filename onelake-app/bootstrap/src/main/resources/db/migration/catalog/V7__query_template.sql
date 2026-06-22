CREATE TABLE IF NOT EXISTS catalog.query_template (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id     UUID NOT NULL,
  owner_id      UUID,
  owner_name    VARCHAR(128),
  name          VARCHAR(128) NOT NULL,
  category      VARCHAR(64),
  description   TEXT,
  sql_template  TEXT NOT NULL,
  placeholders  JSONB NOT NULL DEFAULT '[]'::jsonb,
  shared        BOOLEAN NOT NULL DEFAULT false,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, name)
);
CREATE INDEX IF NOT EXISTS idx_query_template_tenant_updated
  ON catalog.query_template (tenant_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_query_template_tenant_category
  ON catalog.query_template (tenant_id, category);

COMMENT ON TABLE catalog.query_template IS 'SQL 工作台查询模板：团队级 SQL 资产沉淀，支持占位符渲染（Sprint 5a）';
COMMENT ON COLUMN catalog.query_template.placeholders IS '[{"name":"dt","type":"date","required":true,"default":"2026-01-01","description":"业务日期"}]';
