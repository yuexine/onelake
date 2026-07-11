package com.onelake.security.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.outbox.DomainEvents;
import com.onelake.common.outbox.OutboxPublisher;
import com.onelake.common.util.JsonUtil;
import com.onelake.security.domain.entity.*;
import com.onelake.security.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
    private final ObjectProvider<OutboxPublisher> outboxPublisher;

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

    @Transactional
    public List<AccessGrant> myGrants() {
        UUID tenantId = TenantContext.getTenantId();
        UUID subjectId = TenantContext.getUserId();
        if (tenantId == null) throw new BizException(40100, "租户上下文缺失");
        if (subjectId == null) throw new BizException(40100, "用户上下文缺失");
        return activeUnexpiredGrants(tenantId, subjectId);
    }

    @Transactional(readOnly = true)
    public List<AccessGrant> activeGrants(UUID subjectId) {
        return grantRepo.findBySubjectIdAndStatus(subjectId, "ACTIVE");
    }

    @Transactional(readOnly = true)
    public void requireQueryAccess(Collection<String> assetFqns) {
        if (assetFqns == null || assetFqns.isEmpty()) {
            return;
        }
        UUID tenantId = TenantContext.getTenantId();
        UUID subjectId = TenantContext.getUserId();
        if (tenantId == null) {
            throw new BizException(40100, "租户上下文缺失");
        }
        if (subjectId == null) {
            throw new BizException(40100, "用户上下文缺失");
        }
        Set<String> allowed = activeUnexpiredGrants(tenantId, subjectId)
            .stream()
            .filter(this::hasQueryPermission)
            .map(AccessGrant::getAssetFqn)
            .collect(Collectors.toSet());
        for (String fqn : assetFqns) {
            if (!allowed.contains(fqn)) {
                throw new BizException(40340, "无权查询资产: " + fqn);
            }
        }
    }

    private boolean hasQueryPermission(AccessGrant grant) {
        if (grant.getPermissions() == null || grant.getPermissions().isBlank()) {
            return false;
        }
        try {
            return JsonUtil.parse(grant.getPermissions()).path("query").asBoolean(false);
        } catch (IllegalStateException e) {
            return false;
        }
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> maskRows(
        List<Map<String, Object>> rows,
        Map<String, FieldProtection> protectionsByColumn
    ) {
        return maskRowsWithNotices(rows, protectionsByColumn).rows();
    }

    @Transactional(readOnly = true)
    public MaskingResult maskRowsWithNotices(
        List<Map<String, Object>> rows,
        Map<String, FieldProtection> protectionsByColumn
    ) {
        if (rows == null || rows.isEmpty() || protectionsByColumn == null || protectionsByColumn.isEmpty()) {
            return new MaskingResult(rows, List.of(), List.of());
        }
        List<Map<String, Object>> maskedRows = new ArrayList<>(rows.size());
        Map<String, String> maskedColumns = new LinkedHashMap<>();
        Map<String, String> notices = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> masked = new LinkedHashMap<>(row);
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                FieldProtection protection = protectionsByColumn.get(entry.getKey().toLowerCase(Locale.ROOT));
                if (protection != null) {
                    String strategy = resolveMaskingStrategy(protection);
                    if (strategy != null) {
                        masked.put(entry.getKey(), maskValue(entry.getValue(), strategy));
                        maskedColumns.putIfAbsent(entry.getKey(), entry.getKey());
                        notices.putIfAbsent(entry.getKey(), notice(entry.getKey(), protection, strategy));
                    }
                }
            }
            maskedRows.add(masked);
        }
        return new MaskingResult(
            maskedRows,
            List.copyOf(maskedColumns.values()),
            List.copyOf(notices.values())
        );
    }

    private Object maskValue(Object value, FieldProtection protection) {
        if (value == null) {
            return null;
        }
        String strategy = resolveMaskingStrategy(protection);
        if (strategy == null) {
            return value;
        }
        return maskValue(value, strategy);
    }

    private Object maskValue(Object value, String strategy) {
        if (value == null) {
            return null;
        }
        return switch (strategy) {
            case "NULLIFY" -> null;
            case "HASH" -> sha256(value);
            case "MASK" -> "******";
            case "PARTIAL" -> partialMask(value);
            default -> "******";
        };
    }

    private String notice(String column, FieldProtection protection, String strategy) {
        String level = protection.suggestLevel() != null ? protection.suggestLevel() : protection.classification();
        StringBuilder message = new StringBuilder("字段 ").append(column).append(" 已按 ");
        if (level != null && !level.isBlank()) {
            message.append(level).append(" ");
        }
        if (protection.piiType() != null && !protection.piiType().isBlank()) {
            message.append(protection.piiType()).append(" ");
        }
        message.append("策略脱敏");
        if (strategy != null && !strategy.isBlank()) {
            message.append("（").append(strategy).append("）");
        }
        return message.toString();
    }

    private String resolveMaskingStrategy(FieldProtection protection) {
        UUID tenantId = TenantContext.getTenantId();
        Collection<String> targetFqns = protection.targetFqns() == null || protection.targetFqns().isEmpty()
            ? List.of(protection.targetFqn())
            : protection.targetFqns();
        List<MaskingPolicy> policies = targetFqns.stream()
            .flatMap(targetFqn -> maskingRepo.findByTenantIdAndTargetFqn(tenantId, targetFqn).stream())
            .filter(this::appliesToCurrentRole)
            .sorted(Comparator.comparing(MaskingPolicy::getPriority, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
            .toList();
        if (!policies.isEmpty()) {
            return normalizeStrategy(policies.get(0).getStrategy());
        }
        if (isSensitive(protection)) {
            return "PARTIAL";
        }
        return null;
    }

    private boolean appliesToCurrentRole(MaskingPolicy policy) {
        String roleScope = policy.getRoleScope();
        if (roleScope == null || roleScope.isBlank()) {
            return true;
        }
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        String expected = roleScope.startsWith("ROLE_") ? roleScope : "ROLE_" + roleScope;
        return auth.getAuthorities().stream().anyMatch(a ->
            roleScope.equals(a.getAuthority()) || expected.equals(a.getAuthority())
        );
    }

    private boolean isSensitive(FieldProtection protection) {
        String level = normalizeLevel(protection.suggestLevel() != null ? protection.suggestLevel() : protection.classification());
        return protection.piiType() != null
            || "L3".equals(level)
            || "L4".equals(level);
    }

    private String normalizeLevel(String level) {
        return level == null ? "" : level.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeStrategy(String strategy) {
        return strategy == null ? "MASK" : strategy.trim().toUpperCase(Locale.ROOT);
    }

    private String partialMask(Object value) {
        String text = String.valueOf(value);
        if (text.isBlank()) {
            return text;
        }
        int at = text.indexOf('@');
        if (at > 1) {
            return text.charAt(0) + "***" + text.substring(at);
        }
        if (text.length() <= 4) {
            return "****";
        }
        int suffix = text.length() > 8 ? 4 : Math.min(4, Math.max(1, text.length() / 3));
        int prefix = Math.min(3, Math.max(1, text.length() - suffix - 1));
        if (prefix + suffix >= text.length()) {
            return "****";
        }
        return text.substring(0, prefix) + "****" + text.substring(text.length() - suffix);
    }

    private String sha256(Object value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(16, hash.length); i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public record FieldProtection(
        String targetFqn,
        String classification,
        String piiType,
        String suggestLevel,
        Collection<String> targetFqns
    ) {
        public FieldProtection(String targetFqn, String classification, String piiType, String suggestLevel) {
            this(targetFqn, classification, piiType, suggestLevel, List.of(targetFqn));
        }
    }

    public record MaskingResult(
        List<Map<String, Object>> rows,
        List<String> maskedColumns,
        List<String> securityNotices
    ) {}

    /** 资产访问申请（提交进入审批中心）。 */
    @Transactional
    public ApprovalRequest applyAccess(String assetFqn, java.util.Map<String, Object> payload) {
        UUID tenantId = TenantContext.getTenantId();
        UUID applicantId = TenantContext.getUserId();
        if (tenantId == null) throw new BizException(40100, "租户上下文缺失");
        if (applicantId == null) throw new BizException(40100, "用户上下文缺失");
        ApprovalRequest existing = approvalRepo
            .findFirstByTenantIdAndApplicantIdAndTargetRefAndStatusOrderByCreatedAtDesc(tenantId, applicantId, assetFqn, "PENDING")
            .orElse(null);
        if (existing != null) {
            return existing;
        }

        ApprovalRequest req = new ApprovalRequest();
        req.setTenantId(tenantId);
        req.setRequestType("ACCESS");
        req.setApplicantId(applicantId);
        req.setTargetRef(assetFqn);
        req.setPayload(JsonUtil.toJson(enrichAccessPayload(payload)));
        req.setStatus("PENDING");
        return approvalRepo.save(req);
    }

    /** 物理 Schema 变更申请（仅进入审批中心，不直接执行 DDL）。 */
    @Transactional
    public ApprovalRequest applySchemaChange(String assetFqn, Map<String, Object> payload) {
        UUID tenantId = TenantContext.getTenantId();
        UUID applicantId = TenantContext.getUserId();
        if (tenantId == null) throw new BizException(40100, "租户上下文缺失");
        if (applicantId == null) throw new BizException(40100, "用户上下文缺失");
        String targetRef = cleanRequired(assetFqn, "变更资产不能为空");
        ApprovalRequest existing = approvalRepo
            .findFirstByTenantIdAndApplicantIdAndRequestTypeAndTargetRefAndStatusOrderByCreatedAtDesc(
                tenantId,
                applicantId,
                "SCHEMA_CHANGE",
                targetRef,
                "PENDING"
            )
            .orElse(null);
        if (existing != null) {
            return existing;
        }

        ApprovalRequest req = new ApprovalRequest();
        req.setTenantId(tenantId);
        req.setRequestType("SCHEMA_CHANGE");
        req.setApplicantId(applicantId);
        req.setTargetRef(targetRef);
        req.setPayload(JsonUtil.toJson(enrichSchemaChangePayload(payload)));
        req.setStatus("PENDING");
        return approvalRepo.save(req);
    }

    /** 由跨模块事件创建流水线发布审批；同一 DAG、同一 snapshot checksum 只保留一张待审批单。 */
    @Transactional
    public ApprovalRequest applyPublish(UUID tenantId,
                                        UUID applicantId,
                                        String dagId,
                                        Map<String, Object> payload) {
        if (tenantId == null) throw new BizException(40100, "租户上下文缺失");
        if (applicantId == null) throw new BizException(40100, "用户上下文缺失");
        String targetRef = cleanRequired(dagId, "流水线 ID 不能为空");
        Object checksumValue = payload == null ? null : payload.get("snapshotChecksum");
        String snapshotChecksum = cleanRequired(
            checksumValue == null ? null : checksumValue.toString(), "发布快照 checksum 不能为空");
        ApprovalRequest existing = approvalRepo
            .findByTenantIdAndRequestTypeAndTargetRefAndStatus(tenantId, "PUBLISH", targetRef, "PENDING")
            .stream()
            .filter(candidate -> snapshotChecksum.equals(
                String.valueOf(payloadMap(candidate.getPayload()).get("snapshotChecksum"))))
            .findFirst()
            .orElse(null);
        if (existing != null) {
            return existing;
        }

        Map<String, Object> summary = payload == null
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(payload);
        summary.put("snapshotChecksum", snapshotChecksum);
        ApprovalRequest req = new ApprovalRequest();
        req.setTenantId(tenantId);
        req.setRequestType("PUBLISH");
        req.setApplicantId(applicantId);
        req.setTargetRef(targetRef);
        req.setPayload(JsonUtil.toJson(summary));
        req.setStatus("PENDING");
        return approvalRepo.save(req);
    }

    /** 审批通过 → 生成 AccessGrant。 */
    @Transactional
    public AccessGrant approve(UUID approvalId, UUID approverId, String comment) {
        ApprovalRequest req = approvalRepo.findById(approvalId)
            .orElseThrow(() -> new BizException(40400, "审批单不存在"));
        if (!"PENDING".equals(req.getStatus())) {
            throw new BizException(40900, "审批单已处理");
        }
        Map<String, Object> payload = payloadMap(req.getPayload());
        if ("PUBLISH".equals(req.getRequestType())) {
            List<Map<String, Object>> chain = approvalChain(payload);
            Map<String, Object> pendingStep = chain.stream()
                .filter(step -> "PENDING".equals(step.get("status")))
                .findFirst()
                .orElseThrow(() -> new BizException(40900, "审批链已处理完成"));
            boolean alreadyApprovedBySameUser = chain.stream()
                .filter(step -> "APPROVED".equals(step.get("status")))
                .map(step -> String.valueOf(step.get("approverId")))
                .anyMatch(approverId.toString()::equals);
            if (alreadyApprovedBySameUser) {
                throw new BizException(40910, "加签审批需由另一审批人复核");
            }
            completeApprovalStep(pendingStep, "APPROVED", approverId, comment);
            payload.put("approvalChain", chain);
            req.setPayload(JsonUtil.toJson(payload));
            req.setComment(comment);
            if (chain.stream().anyMatch(step -> "PENDING".equals(step.get("status")))) {
                return null;
            }
            req.setStatus("APPROVED");
            req.setApproverId(approverId);
            req.setDecidedAt(Instant.now());
            emitApprovalDecision(req, "APPROVED");
            return null;
        }
        if (!"ACCESS".equals(req.getRequestType())) {
            markPendingSteps(payload, "APPROVED", approverId, comment);
            req.setPayload(JsonUtil.toJson(payload));
            req.setStatus("APPROVED");
            req.setApproverId(approverId);
            req.setComment(comment);
            req.setDecidedAt(Instant.now());
            emitApprovalDecision(req, "APPROVED");
            return null;
        }
        if (requiresSecondApproval(payload) && !firstApprovalDone(payload)) {
            markApprovalStep(payload, "ASSET_OWNER", approverId, comment);
            req.setPayload(JsonUtil.toJson(payload));
            req.setComment(comment);
            return null;
        }
        if (requiresSecondApproval(payload) && firstApprovalBySameApprover(payload, approverId)) {
            throw new BizException(40910, "高危权限需第二审批人复核");
        }
        if (requiresSecondApproval(payload)) {
            markApprovalStep(payload, "SECURITY_REVIEW", approverId, comment);
            req.setPayload(JsonUtil.toJson(payload));
        }
        req.setStatus("APPROVED");
        req.setApproverId(approverId);
        req.setComment(comment);
        Instant now = Instant.now();
        req.setDecidedAt(now);

        AccessPolicy accessPolicy = resolveAccessPolicy(req.getPayload(), now);

        AccessGrant g = new AccessGrant();
        g.setTenantId(req.getTenantId());
        g.setSubjectId(req.getApplicantId());
        g.setAssetFqn(req.getTargetRef());
        g.setColumns(accessPolicy.columns());
        g.setPermissions(JsonUtil.toJson(accessPolicy.permissions()));
        g.setStatus("ACTIVE");
        g.setGrantedAt(now);
        g.setExpiresAt(accessPolicy.expiresAt());
        return grantRepo.save(g);
    }

    @Transactional
    public void reject(UUID approvalId, UUID approverId, String comment) {
        ApprovalRequest req = approvalRepo.findById(approvalId)
            .orElseThrow(() -> new BizException(40400, "审批单不存在"));
        if (!"PENDING".equals(req.getStatus())) {
            throw new BizException(40900, "审批单已处理");
        }
        req.setStatus("REJECTED");
        req.setApproverId(approverId);
        req.setComment(comment);
        req.setDecidedAt(Instant.now());
        Map<String, Object> payload = payloadMap(req.getPayload());
        markPendingSteps(payload, "REJECTED", approverId, comment);
        req.setPayload(JsonUtil.toJson(payload));
        emitApprovalDecision(req, "REJECTED");
    }

    /** 审批中心通过 Outbox 通知业务模块，避免直接调用或写入业务模块数据库。 */
    private void emitApprovalDecision(ApprovalRequest req, String decision) {
        if (!"PUBLISH".equals(req.getRequestType())) {
            return;
        }
        OutboxPublisher publisher = outboxPublisher.getIfAvailable();
        if (publisher == null) {
            throw new BizException(50320, "审批结果事件发布器不可用");
        }
        Map<String, Object> requestPayload = payloadMap(req.getPayload());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("approvalId", req.getId() == null ? null : req.getId().toString());
        payload.put("requestType", req.getRequestType());
        payload.put("targetRef", req.getTargetRef());
        payload.put("tenantId", req.getTenantId().toString());
        payload.put("applicantId", req.getApplicantId().toString());
        payload.put("applicantName", requestPayload.get("applicantName"));
        payload.put("snapshotChecksum", requestPayload.get("snapshotChecksum"));
        payload.put("decision", decision);
        payload.put("reason", req.getComment());
        String aggregateId = req.getId() == null ? req.getTargetRef() : req.getId().toString();
        publisher.publish(DomainEvents.SECURITY_APPROVAL_DECIDED, aggregateId, payload);
    }

    @Transactional
    public void cancelMyApproval(UUID approvalId, String comment) {
        UUID tenantId = TenantContext.getTenantId();
        UUID applicantId = TenantContext.getUserId();
        if (tenantId == null) throw new BizException(40100, "租户上下文缺失");
        if (applicantId == null) throw new BizException(40100, "用户上下文缺失");
        ApprovalRequest req = approvalRepo.findById(approvalId)
            .orElseThrow(() -> new BizException(40400, "审批单不存在"));
        if (!tenantId.equals(req.getTenantId()) || !applicantId.equals(req.getApplicantId())) {
            throw new BizException(40300, "无权撤回该审批单");
        }
        if (!"PENDING".equals(req.getStatus())) {
            throw new BizException(40900, "审批单已处理");
        }
        req.setStatus("CANCELED");
        req.setComment(comment);
        req.setDecidedAt(Instant.now());
    }

    @Transactional(readOnly = true)
    public List<ApprovalRequest> pendingApprovals() {
        return approvalRepo.findByTenantIdAndStatus(TenantContext.getTenantId(), "PENDING");
    }

    @Transactional
    public List<AccessGrant> listGrants(String status) {
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null) throw new BizException(40100, "租户上下文缺失");
        expireTenantGrants(tenantId);
        return grantRepo.findByTenantIdAndStatusInOrderByGrantedAtDesc(tenantId, grantStatuses(status));
    }

    @Transactional
    public AccessGrant createGrant(UUID subjectId, String assetFqn, Map<String, Object> payload) {
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null) throw new BizException(40100, "租户上下文缺失");
        if (subjectId == null) throw new BizException(40050, "授权用户不能为空");
        if (assetFqn == null || assetFqn.isBlank()) throw new BizException(40050, "授权资产不能为空");
        Instant now = Instant.now();
        AccessPolicy accessPolicy = resolveAccessPolicy(JsonUtil.toJson(payload == null ? Map.of() : payload), now);
        AccessGrant grant = new AccessGrant();
        grant.setTenantId(tenantId);
        grant.setSubjectId(subjectId);
        grant.setAssetFqn(assetFqn.trim());
        grant.setColumns(accessPolicy.columns());
        grant.setPermissions(JsonUtil.toJson(accessPolicy.permissions()));
        grant.setStatus("ACTIVE");
        grant.setGrantedAt(now);
        grant.setExpiresAt(accessPolicy.expiresAt());
        return grantRepo.save(grant);
    }

    @Transactional
    public AccessGrant revokeGrant(UUID grantId, String comment) {
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null) throw new BizException(40100, "租户上下文缺失");
        AccessGrant grant = grantRepo.findByTenantIdAndId(tenantId, grantId)
            .orElseThrow(() -> new BizException(40400, "授权不存在"));
        grant.setStatus("REVOKED");
        return grant;
    }

    @Transactional
    public AccessGrant extendGrant(UUID grantId, int durationDays) {
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null) throw new BizException(40100, "租户上下文缺失");
        if (durationDays < 1 || durationDays > 366) {
            throw new BizException(40050, "延长期限需在 1 到 366 天之间");
        }
        AccessGrant grant = grantRepo.findByTenantIdAndId(tenantId, grantId)
            .orElseThrow(() -> new BizException(40400, "授权不存在"));
        if ("REVOKED".equals(grant.getStatus())) {
            throw new BizException(40900, "授权已撤销");
        }
        Instant base = grant.getExpiresAt() != null && grant.getExpiresAt().isAfter(Instant.now())
            ? grant.getExpiresAt()
            : Instant.now();
        grant.setStatus("ACTIVE");
        grant.setExpiresAt(base.plus(Math.min(durationDays, 366), ChronoUnit.DAYS));
        return grant;
    }

    @Transactional
    public ApprovalRequest transferApproval(UUID approvalId, UUID nextApproverId, String comment) {
        ApprovalRequest req = pendingApproval(approvalId);
        Map<String, Object> payload = payloadMap(req.getPayload());
        payload.put("assignedApproverId", nextApproverId == null ? null : nextApproverId.toString());
        appendWorkflowEvent(payload, "TRANSFER", TenantContext.getUserId(), comment);
        req.setPayload(JsonUtil.toJson(payload));
        req.setComment(comment);
        return req;
    }

    @Transactional
    public ApprovalRequest addSign(UUID approvalId, String role, String comment) {
        ApprovalRequest req = pendingApproval(approvalId);
        Map<String, Object> payload = payloadMap(req.getPayload());
        List<Map<String, Object>> chain = approvalChain(payload);
        chain.add(new LinkedHashMap<>(Map.of(
            "role", role == null || role.isBlank() ? "ADDITIONAL_REVIEW" : role,
            "status", "PENDING"
        )));
        payload.put("approvalChain", chain);
        appendWorkflowEvent(payload, "ADD_SIGN", TenantContext.getUserId(), comment);
        req.setPayload(JsonUtil.toJson(payload));
        req.setComment(comment);
        return req;
    }

    @Transactional(readOnly = true)
    public Page<ApprovalRequest> myApprovals(String status, Pageable pageable) {
        UUID tenantId = TenantContext.getTenantId();
        UUID applicantId = TenantContext.getUserId();
        if (tenantId == null) throw new BizException(40100, "租户上下文缺失");
        if (applicantId == null) throw new BizException(40100, "用户上下文缺失");
        return approvalRepo.findByTenantIdAndApplicantIdAndStatusInOrderByCreatedAtDesc(
            tenantId,
            applicantId,
            approvalStatuses(status),
            pageable
        );
    }

    /** 返回租户内指定流水线最近一次发布审批，供协作编辑者共享等待/拒绝状态。 */
    @Transactional(readOnly = true)
    public ApprovalRequest latestPublishApproval(String dagId) {
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null) throw new BizException(40100, "租户上下文缺失");
        String targetRef = cleanRequired(dagId, "流水线 ID 不能为空");
        return approvalRepo
            .findFirstByTenantIdAndRequestTypeAndTargetRefOrderByCreatedAtDesc(
                tenantId, "PUBLISH", targetRef)
            .orElse(null);
    }

    @Transactional(readOnly = true)
    public Page<ApprovalRequest> processedApprovals(String status, Pageable pageable) {
        return approvalRepo.findByTenantIdAndStatusInOrderByDecidedAtDescCreatedAtDesc(
            TenantContext.getTenantId(),
            processedStatuses(status),
            pageable
        );
    }

    private List<String> processedStatuses(String status) {
        return approvalStatuses(status, List.of("APPROVED", "REJECTED", "CANCELED"));
    }

    private List<String> approvalStatuses(String status) {
        return approvalStatuses(status, List.of("PENDING", "APPROVED", "REJECTED", "CANCELED"));
    }

    private List<String> approvalStatuses(String status, List<String> allowed) {
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)) {
            return allowed;
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new BizException(40050, "审批状态不支持: " + status);
        }
        return List.of(normalized);
    }

    private List<AccessGrant> activeUnexpiredGrants(UUID tenantId, UUID subjectId) {
        Instant now = Instant.now();
        return grantRepo.findByTenantIdAndSubjectIdAndStatus(tenantId, subjectId, "ACTIVE")
            .stream()
            .filter(grant -> {
                if (grant.getExpiresAt() == null || grant.getExpiresAt().isAfter(now)) {
                    return true;
                }
                grant.setStatus("EXPIRED");
                return false;
            })
            .toList();
    }

    private void expireTenantGrants(UUID tenantId) {
        Instant now = Instant.now();
        grantRepo.findByTenantIdAndStatus(tenantId, "ACTIVE").stream()
            .filter(grant -> grant.getExpiresAt() != null && !grant.getExpiresAt().isAfter(now))
            .forEach(grant -> grant.setStatus("EXPIRED"));
    }

    private List<String> grantStatuses(String status) {
        List<String> allowed = List.of("ACTIVE", "EXPIRED", "REVOKED");
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)) {
            return allowed;
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new BizException(40050, "授权状态不支持: " + status);
        }
        return List.of(normalized);
    }

    private Map<String, Object> enrichAccessPayload(Map<String, Object> payload) {
        Map<String, Object> enriched = new LinkedHashMap<>(payload == null ? Map.of() : payload);
        Map<String, Object> permissions = objectMap(enriched.get("permissions"));
        permissions.putIfAbsent("query", true);
        permissions.putIfAbsent("download", false);
        permissions.putIfAbsent("api", false);
        enriched.put("permissions", permissions);
        String riskLevel = resolveRiskLevel(enriched, permissions);
        enriched.put("riskLevel", riskLevel);
        enriched.putIfAbsent("approvalChain", defaultApprovalChain(riskLevel));
        enriched.putIfAbsent("impactSummary", Map.of(
            "assets", 1,
            "apis", Boolean.TRUE.equals(permissions.get("api")) ? 1 : 0,
            "subscribers", 0
        ));
        return enriched;
    }

    private Map<String, Object> enrichSchemaChangePayload(Map<String, Object> payload) {
        Map<String, Object> enriched = new LinkedHashMap<>(payload == null ? Map.of() : payload);
        Object changeTypeValue = enriched.get("changeType");
        String changeType = cleanRequired(changeTypeValue == null ? null : String.valueOf(changeTypeValue), "Schema 变更类型不能为空")
            .toUpperCase(Locale.ROOT);
        if (!Set.of("ADD_COLUMN", "DROP_COLUMN", "RENAME_COLUMN", "CHANGE_TYPE").contains(changeType)) {
            throw new BizException(40050, "Schema 变更类型不支持: " + changeType);
        }
        String riskLevel = switch (changeType) {
            case "DROP_COLUMN", "CHANGE_TYPE" -> "HIGH";
            case "RENAME_COLUMN" -> "MEDIUM";
            default -> "LOW";
        };
        enriched.put("changeType", changeType);
        enriched.putIfAbsent("riskLevel", riskLevel);
        enriched.putIfAbsent("reason", "Schema 变更申请");
        enriched.putIfAbsent("approvalChain", List.of(new LinkedHashMap<>(Map.of(
            "role",
            "DATA_OWNER",
            "status",
            "PENDING"
        ))));
        enriched.putIfAbsent("impactSummary", Map.of(
            "assets", 1,
            "apis", 0,
            "subscribers", 0
        ));
        enriched.put("executionMode", "APPROVAL_ONLY");
        return enriched;
    }

    private String cleanRequired(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BizException(40050, message);
        }
        return value.trim();
    }

    private String resolveRiskLevel(Map<String, Object> payload, Map<String, Object> permissions) {
        int durationDays = numberValue(payload.get("durationDays"));
        boolean elevated = Boolean.TRUE.equals(permissions.get("download")) || Boolean.TRUE.equals(permissions.get("api"));
        if (Boolean.TRUE.equals(permissions.get("api")) || durationDays == 0 || durationDays > 90) {
            return "HIGH";
        }
        return elevated ? "MEDIUM" : "LOW";
    }

    private List<Map<String, Object>> defaultApprovalChain(String riskLevel) {
        List<Map<String, Object>> chain = new ArrayList<>();
        chain.add(new LinkedHashMap<>(Map.of("role", "ASSET_OWNER", "status", "PENDING")));
        if ("HIGH".equals(riskLevel)) {
            chain.add(new LinkedHashMap<>(Map.of("role", "SECURITY_REVIEW", "status", "PENDING")));
        }
        return chain;
    }

    private boolean requiresSecondApproval(Map<String, Object> payload) {
        return "HIGH".equals(String.valueOf(payload.getOrDefault("riskLevel", "LOW")));
    }

    private boolean firstApprovalDone(Map<String, Object> payload) {
        return approvalChain(payload).stream()
            .anyMatch(step -> "ASSET_OWNER".equals(step.get("role")) && "APPROVED".equals(step.get("status")));
    }

    private boolean firstApprovalBySameApprover(Map<String, Object> payload, UUID approverId) {
        return approvalChain(payload).stream()
            .filter(step -> "ASSET_OWNER".equals(step.get("role")))
            .map(step -> String.valueOf(step.get("approverId")))
            .anyMatch(approverId.toString()::equals);
    }

    private void markApprovalStep(Map<String, Object> payload, String role, UUID approverId, String comment) {
        List<Map<String, Object>> chain = approvalChain(payload);
        chain.stream()
            .filter(step -> role.equals(step.get("role")) && !"APPROVED".equals(step.get("status")))
            .findFirst()
            .ifPresent(step -> {
                step.put("status", "APPROVED");
                step.put("approverId", approverId.toString());
                step.put("comment", comment);
                step.put("at", Instant.now().toString());
            });
        payload.put("approvalChain", chain);
    }

    private void completeApprovalStep(Map<String, Object> step,
                                      String status,
                                      UUID approverId,
                                      String comment) {
        step.put("status", status);
        step.put("approverId", approverId.toString());
        step.put("comment", comment);
        step.put("at", Instant.now().toString());
    }

    private void markPendingSteps(Map<String, Object> payload, String status, UUID approverId, String comment) {
        List<Map<String, Object>> chain = approvalChain(payload);
        chain.stream()
            .filter(step -> "PENDING".equals(step.get("status")))
            .forEach(step -> {
                step.put("status", status);
                step.put("approverId", approverId.toString());
                step.put("comment", comment);
                step.put("at", Instant.now().toString());
            });
        payload.put("approvalChain", chain);
    }

    private void appendWorkflowEvent(Map<String, Object> payload, String action, UUID actorId, String comment) {
        List<Map<String, Object>> events = listOfMaps(payload.get("workflowEvents"));
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("action", action);
        event.put("actorId", actorId == null ? null : actorId.toString());
        event.put("comment", comment);
        event.put("at", Instant.now().toString());
        events.add(event);
        payload.put("workflowEvents", events);
    }

    private ApprovalRequest pendingApproval(UUID approvalId) {
        ApprovalRequest req = approvalRepo.findById(approvalId)
            .orElseThrow(() -> new BizException(40400, "审批单不存在"));
        if (!"PENDING".equals(req.getStatus())) {
            throw new BizException(40900, "审批单已处理");
        }
        return req;
    }

    private Map<String, Object> payloadMap(String payload) {
        if (payload == null || payload.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return JsonUtil.mapper().readValue(payload, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((k, v) -> result.put(String.valueOf(k), v));
            return result;
        }
        return new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> approvalChain(Map<String, Object> payload) {
        List<Map<String, Object>> chain = listOfMaps(payload.get("approvalChain"));
        if (chain.isEmpty()) {
            chain = defaultApprovalChain(String.valueOf(payload.getOrDefault("riskLevel", "LOW")));
        }
        return chain;
    }

    private List<Map<String, Object>> listOfMaps(Object value) {
        if (!(value instanceof List<?> list)) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> row = new LinkedHashMap<>();
                map.forEach((k, v) -> row.put(String.valueOf(k), v));
                result.add(row);
            }
        }
        return result;
    }

    private int numberValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private AccessPolicy resolveAccessPolicy(String payload, Instant grantedAt) {
        Map<String, Boolean> permissions = new LinkedHashMap<>();
        permissions.put("query", true);
        permissions.put("download", false);
        permissions.put("api", false);
        String columns = null;
        Instant expiresAt = null;

        if (payload == null || payload.isBlank()) {
            return new AccessPolicy(permissions, columns, expiresAt);
        }

        try {
            JsonNode root = JsonUtil.parse(payload);
            JsonNode permissionNode = root.path("permissions");
            if (permissionNode.isMissingNode() || permissionNode.isNull()) {
                permissionNode = root.path("requestedPermissions");
            }
            if (permissionNode.isObject()) {
                permissions.put("query", permissionNode.path("query").asBoolean(true));
                permissions.put("download", permissionNode.path("download").asBoolean(false));
                permissions.put("api", permissionNode.path("api").asBoolean(false));
            }
            JsonNode columnsNode = root.path("columns");
            if (columnsNode.isArray()) {
                columns = JsonUtil.toJson(columnsNode);
            }
            int durationDays = root.path("durationDays").asInt(0);
            if (durationDays > 0) {
                expiresAt = grantedAt.plus(Math.min(durationDays, 366), ChronoUnit.DAYS);
            }
        } catch (IllegalStateException ignored) {
            return new AccessPolicy(permissions, columns, expiresAt);
        }
        return new AccessPolicy(permissions, columns, expiresAt);
    }

    private record AccessPolicy(
        Map<String, Boolean> permissions,
        String columns,
        Instant expiresAt
    ) {}
}
