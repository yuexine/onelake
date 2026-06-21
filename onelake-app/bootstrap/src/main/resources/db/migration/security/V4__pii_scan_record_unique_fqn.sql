DELETE FROM security.pii_scan_record a
USING security.pii_scan_record b
WHERE a.ctid < b.ctid
  AND a.tenant_id = b.tenant_id
  AND a.fqn = b.fqn;

CREATE UNIQUE INDEX IF NOT EXISTS uq_pii_scan_tenant_fqn
  ON security.pii_scan_record (tenant_id, fqn);
