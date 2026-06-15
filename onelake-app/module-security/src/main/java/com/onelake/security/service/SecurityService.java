package com.onelake.security.service;

import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.security.domain.entity.*;
import com.onelake.security.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 安全与权限服务（密级 / 脱敏策略 / 授权 / 审批）。
 */
@Service
@RequiredArgsConstructor
public class SecurityService {

    private final MaskingPolicyRepository maskingRepo;
    private final AccessGrantRepository grantRepo;
    private final ApprovalRequestRepository approvalRepo;
    private final SecretRepository secretRepo;

    /** 按密级/角色查询脱敏策略；冲突时取最高优先级。 */
    @Transactional(readOnly = true)
    public MaskingPolicy resolveMasking(String targetFqn) {
        List<MaskingPolicy> all = maskingRepo.findByTargetFqnOrderByPriorityDesc(targetFqn);
        return all.isEmpty() ? null : all.get(0);
    }

    @Transactional
    public MaskingPolicy createMasking(MaskingPolicy p) {
        p.setTenantId(TenantContext.getTenantId());
        if (p.getPriority() == null) p.setPriority(100);
        return maskingRepo.save(p);
    }

    @Transactional(readOnly = true)
    public List<AccessGrant> myGrants() {
        UUID subjectId = TenantContext.getUserId();
        if (subjectId == null) throw new BizException(40100, "用户上下文缺失");
        return grantRepo.findBySubjectId(subjectId);
    }

    @Transactional(readOnly = true)
    public List<AccessGrant> activeGrants(UUID subjectId) {
        return grantRepo.findBySubjectIdAndStatus(subjectId, "ACTIVE");
    }

    /** 资产访问申请（提交进入审批中心）。 */
    @Transactional
    public ApprovalRequest applyAccess(String assetFqn, java.util.Map<String, Object> payload) {
        ApprovalRequest req = new ApprovalRequest();
        req.setTenantId(TenantContext.getTenantId());
        req.setRequestType("ACCESS");
        req.setApplicantId(TenantContext.getUserId());
        req.setTargetRef(assetFqn);
        req.setPayload(com.onelake.common.util.JsonUtil.toJson(payload));
        req.setStatus("PENDING");
        return approvalRepo.save(req);
    }

    /** 审批通过 → 生成 AccessGrant。 */
    @Transactional
    public AccessGrant approve(UUID approvalId, UUID approverId, String comment) {
        ApprovalRequest req = approvalRepo.findById(approvalId)
            .orElseThrow(() -> new BizException(40400, "审批单不存在"));
        req.setStatus("APPROVED");
        req.setApproverId(approverId);
        req.setComment(comment);
        req.setDecidedAt(Instant.now());

        AccessGrant g = new AccessGrant();
        g.setTenantId(req.getTenantId());
        g.setSubjectId(req.getApplicantId());
        g.setAssetFqn(req.getTargetRef());
        g.setPermissions("{\"query\":true,\"download\":false,\"api\":true}");
        g.setStatus("ACTIVE");
        g.setGrantedAt(Instant.now());
        return grantRepo.save(g);
    }

    @Transactional
    public void reject(UUID approvalId, UUID approverId, String comment) {
        ApprovalRequest req = approvalRepo.findById(approvalId)
            .orElseThrow(() -> new BizException(40400, "审批单不存在"));
        req.setStatus("REJECTED");
        req.setApproverId(approverId);
        req.setComment(comment);
        req.setDecidedAt(Instant.now());
    }

    @Transactional(readOnly = true)
    public List<ApprovalRequest> pendingApprovals() {
        return approvalRepo.findByTenantIdAndStatus(TenantContext.getTenantId(), "PENDING");
    }
}
