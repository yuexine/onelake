-- =========================================================
-- common schema: 租户/项目/用户缓存/审计/事件/通知/字典/标签
-- 对应《技术初始化文档》§7.1 + §7.9
-- =========================================================
CREATE SCHEMA IF NOT EXISTS common;

CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- 租户
CREATE TABLE IF NOT EXISTS common.tenant (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  code        VARCHAR(64) NOT NULL UNIQUE,
  name        VARCHAR(128) NOT NULL,
  status      VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 项目
CREATE TABLE IF NOT EXISTS common.project (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   UUID NOT NULL REFERENCES common.tenant(id),
  code        VARCHAR(64) NOT NULL,
  name        VARCHAR(128) NOT NULL,
  owner_id    UUID,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, code)
);

-- 用户缓存
CREATE TABLE IF NOT EXISTS common.app_user (
  id          UUID PRIMARY KEY,
  tenant_id   UUID NOT NULL REFERENCES common.tenant(id),
  username    VARCHAR(128) NOT NULL,
  email       VARCHAR(256),
  display_name VARCHAR(128),
  roles       JSONB NOT NULL DEFAULT '[]',
  synced_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 审计日志（只追加）
CREATE TABLE IF NOT EXISTS common.audit_log (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id   UUID NOT NULL,
  actor_id    UUID,
  action      VARCHAR(64) NOT NULL,
  resource_type VARCHAR(64) NOT NULL,
  resource_id VARCHAR(128),
  detail      JSONB,
  trace_id    VARCHAR(64),
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_audit_tenant_time ON common.audit_log (tenant_id, occurred_at DESC);

-- Outbox 事件
CREATE TABLE IF NOT EXISTS common.outbox_event (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  event_type  VARCHAR(128) NOT NULL,
  aggregate_id VARCHAR(128),
  payload     JSONB NOT NULL,
  status      VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_outbox_status ON common.outbox_event (status, occurred_at);

-- 站内通知
CREATE TABLE IF NOT EXISTS common.notification (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   UUID NOT NULL,
  receiver_id UUID NOT NULL,
  category    VARCHAR(32) NOT NULL,
  title       VARCHAR(256) NOT NULL,
  link        VARCHAR(512),
  is_read     BOOLEAN NOT NULL DEFAULT false,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 数据字典
CREATE TABLE IF NOT EXISTS common.dict_type (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   UUID NOT NULL,
  code        VARCHAR(64) NOT NULL,
  name        VARCHAR(128) NOT NULL,
  UNIQUE (tenant_id, code)
);

CREATE TABLE IF NOT EXISTS common.dict_item (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  type_id     UUID NOT NULL REFERENCES common.dict_type(id),
  item_key    VARCHAR(64) NOT NULL,
  item_value  VARCHAR(256) NOT NULL,
  sort_no     INT NOT NULL DEFAULT 0,
  enabled     BOOLEAN NOT NULL DEFAULT true,
  UNIQUE (type_id, item_key)
);

-- 标签
CREATE TABLE IF NOT EXISTS common.tag (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   UUID NOT NULL,
  name        VARCHAR(64) NOT NULL,
  color       VARCHAR(16),
  category    VARCHAR(32),
  UNIQUE (tenant_id, name)
);
