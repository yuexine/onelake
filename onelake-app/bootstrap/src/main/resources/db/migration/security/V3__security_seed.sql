-- security 模块 demo 种子数据（PII 扫描记录）
\set tenant '11111111-1111-1111-1111-111111111111'

INSERT INTO security.pii_scan_record (id, tenant_id, fqn, pii_type, confidence, suggest_level, status, scanned_at, confirmed_at)
VALUES
  ('pppppppp-0001-0000-0000-000000000001', :'tenant', 'ods.users.phone',      '手机号', 0.98, 'L3', 'PENDING',   now(), NULL),
  ('pppppppp-0002-0000-0000-000000000001', :'tenant', 'ods.users.id_card',    '身份证', 0.95, 'L4', 'PENDING',   now(), NULL),
  ('pppppppp-0003-0000-0000-000000000001', :'tenant', 'ods.orders.email',     '邮箱',   0.80, 'L3', 'PENDING',   now(), NULL),
  ('pppppppp-0004-0000-0000-000000000001', :'tenant', 'dwd.dwd_user_df.phone','手机号', 0.99, 'L3', 'CONFIRMED', now() - interval '1 day', now() - interval '12 hours')
ON CONFLICT DO NOTHING;
