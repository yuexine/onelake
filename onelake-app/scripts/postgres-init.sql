-- 控制面 PostgreSQL 初始化脚本
-- 启用 pgcrypto（uuid 主键）+ 创建 web_anon 角色（PostgREST 使用）
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'web_anon') THEN
    CREATE ROLE web_anon NOLOGIN;
    GRANT USAGE ON SCHEMA dataservice_api TO web_anon;
  END IF;
END $$;
