package com.onelake.security.service;

import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.util.JsonUtil;
import com.onelake.security.domain.entity.*;
import com.onelake.security.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
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
        Instant now = Instant.now();
        Set<String> allowed = grantRepo.findByTenantIdAndSubjectIdAndStatus(tenantId, subjectId, "ACTIVE")
            .stream()
            .filter(grant -> grant.getExpiresAt() == null || grant.getExpiresAt().isAfter(now))
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
