-- PII 扫描记录表（对应 security.PiiScanRecord 实体）
CREATE TABLE IF NOT EXISTS security.pii_scan_record (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL,
    fqn           VARCHAR(500) NOT NULL,          -- 库.表.字段
    pii_type      VARCHAR(32) NOT NULL,           -- 手机号/身份证/银行卡/邮箱/姓名
    confidence    DOUBLE PRECISION NOT NULL,      -- 0.0 ~ 1.0
    suggest_level VARCHAR(4),                     -- L3 / L4
    status        VARCHAR(16) NOT NULL DEFAULT 'PENDING',  -- PENDING / CONFIRMED / IGNORED
    scanned_at    TIMESTAMP WITH TIME ZONE DEFAULT now(),
    confirmed_at  TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_pii_scan_tenant_status ON security.pii_scan_record (tenant_id, status);
CREATE INDEX idx_pii_scan_fqn ON security.pii_scan_record (fqn);
