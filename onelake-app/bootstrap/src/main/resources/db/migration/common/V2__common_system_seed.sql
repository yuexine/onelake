-- Seed current development tenant/project options used by Keycloak dev tokens.
INSERT INTO common.tenant (id, code, name, status)
VALUES ('11111111-1111-1111-1111-111111111111', 'TRADE', '交易事业部', 'ACTIVE')
ON CONFLICT (id) DO UPDATE
SET code = EXCLUDED.code,
    name = EXCLUDED.name,
    status = EXCLUDED.status;

INSERT INTO common.project (id, tenant_id, code, name)
VALUES
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1', '11111111-1111-1111-1111-111111111111', 'ORDER', '订单域'),
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2', '11111111-1111-1111-1111-111111111111', 'USER', '用户域'),
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa3', '11111111-1111-1111-1111-111111111111', 'RISK', '风控域')
ON CONFLICT (tenant_id, code) DO UPDATE
SET name = EXCLUDED.name;
