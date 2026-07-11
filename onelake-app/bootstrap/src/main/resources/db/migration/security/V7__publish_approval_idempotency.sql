-- 同一租户、同一流水线、同一待发布内容只允许一张 PENDING 发布审批单。
-- 审批终结后索引条件不再命中，允许相同内容再次发起新的审批流程。
CREATE UNIQUE INDEX IF NOT EXISTS uq_approval_publish_pending_content
  ON security.approval_request (
    tenant_id,
    request_type,
    target_ref,
    ((payload ->> 'snapshotChecksum'))
  )
  WHERE request_type = 'PUBLISH'
    AND status = 'PENDING';
