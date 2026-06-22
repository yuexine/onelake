-- security 模块 demo 种子数据（PII 扫描记录）
INSERT INTO security.pii_scan_record (id, tenant_id, fqn, pii_type, confidence, suggest_level, status, scanned_at, confirmed_at)
VALUES
  ('99999999-0001-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'ods.users.phone',      '手机号', 0.98, 'L3', 'PENDING',   now(), NULL),
  ('99999999-0002-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'ods.users.id_card',    '身份证', 0.95, 'L4', 'PENDING',   now(), NULL),
  ('99999999-0003-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'ods.orders.email',     '邮箱',   0.80, 'L3', 'PENDING',   now(), NULL),
  ('99999999-0004-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'dwd.dwd_user_df.phone','手机号', 0.99, 'L3', 'CONFIRMED', now() - interval '1 day', now() - interval '12 hours')
ON CONFLICT DO NOTHING;
