package com.onelake.common.audit;

import com.onelake.common.context.TenantContext;
import com.onelake.common.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 审计写入器：所有写操作可调用 audit(...) 在独立事务中追加日志，
 * 不影响主业务事务结果（REQUIRES_NEW）。
 */
@Component
@RequiredArgsConstructor
public class AuditLogger {

    private final AuditLogRepository repo;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void audit(String action, String resourceType, String resourceId, Object detail) {
        AuditLog log = new AuditLog();
        log.setTenantId(TenantContext.getTenantId());
        log.setActorId(TenantContext.getUserId());
        log.setAction(action);
        log.setResourceType(resourceType);
        log.setResourceId(resourceId);
        log.setDetail(detail == null ? null : JsonUtil.toJson(detail));
        log.setTraceId(TenantContext.getTraceId());
        log.setOccurredAt(java.time.Instant.now());
        repo.save(log);
    }

    public void auditCreate(String resourceType, UUID resourceId, Object detail) {
        audit("CREATE", resourceType, resourceId == null ? null : resourceId.toString(), detail);
    }

    public void auditUpdate(String resourceType, UUID resourceId, Object detail) {
        audit("UPDATE", resourceType, resourceId == null ? null : resourceId.toString(), detail);
    }

    public void auditDelete(String resourceType, UUID resourceId) {
        audit("DELETE", resourceType, resourceId == null ? null : resourceId.toString(), null);
    }
}
