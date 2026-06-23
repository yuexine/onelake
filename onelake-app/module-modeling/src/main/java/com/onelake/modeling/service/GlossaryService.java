package com.onelake.modeling.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.onelake.common.audit.AuditLogger;
import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.outbox.DomainEvents;
import com.onelake.common.outbox.OutboxPublisher;
import com.onelake.common.util.JsonUtil;
import com.onelake.modeling.domain.entity.BusinessTerm;
import com.onelake.modeling.domain.entity.BusinessTermBinding;
import com.onelake.modeling.domain.entity.BusinessTermVersion;
import com.onelake.modeling.domain.entity.SubjectDomain;
import com.onelake.modeling.dto.BusinessTermBindingDTO;
import com.onelake.modeling.dto.BusinessTermBindingRequest;
import com.onelake.modeling.dto.BusinessTermDTO;
import com.onelake.modeling.dto.BusinessTermImpactDTO;
import com.onelake.modeling.dto.BusinessTermRequest;
import com.onelake.modeling.dto.BusinessTermVersionDTO;
import com.onelake.modeling.dto.BusinessTermVersionDiffDTO;
import com.onelake.modeling.repository.BusinessTermBindingRepository;
import com.onelake.modeling.repository.BusinessTermRepository;
import com.onelake.modeling.repository.BusinessTermVersionRepository;
import com.onelake.modeling.repository.SubjectDomainRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class GlossaryService {

    private final BusinessTermRepository termRepo;
    private final BusinessTermBindingRepository bindingRepo;
    private final BusinessTermVersionRepository versionRepo;
    private final SubjectDomainRepository domainRepo;
    private final OutboxPublisher outboxPublisher;
    private final AuditLogger auditLogger;
    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public List<BusinessTermDTO> list(String keyword, UUID domainId, String status) {
        UUID tenantId = TenantContext.getTenantId();
        String q = normalize(keyword);
        String s = status == null || status.isBlank() ? null : status.toUpperCase(Locale.ROOT);
        return termRepo.findByTenantIdOrderByUpdatedAtDesc(tenantId).stream()
            .filter(term -> domainId == null || domainId.equals(term.getDomainId()))
            .filter(term -> s == null || s.equalsIgnoreCase(term.getStatus()))
            .filter(term -> q.isBlank() || matches(term, q))
            .map(term -> toDTO(term, false))
            .toList();
    }

    @Transactional(readOnly = true)
    public BusinessTermDTO get(UUID id) {
        return toDTO(getTerm(id), true);
    }

    @Transactional
    public BusinessTermDTO create(BusinessTermRequest request) {
        validateRequired(request.code(), "code 必填");
        validateRequired(request.name(), "name 必填");
        UUID tenantId = TenantContext.getTenantId();
        termRepo.findByTenantIdAndCodeIgnoreCase(tenantId, request.code().trim())
            .ifPresent(existing -> {
                throw new BizException(40001, "术语编码已存在");
            });

        BusinessTerm term = new BusinessTerm();
        term.setTenantId(tenantId);
        term.setCode(request.code().trim());
        apply(term, request);
        term.setStatus("DRAFT");
        term.setVersion(1);
        term.setCreatedBy(TenantContext.getUserId());
        term.setUpdatedBy(TenantContext.getUserId());
        term = termRepo.save(term);
        auditLogger.auditCreate("BUSINESS_TERM", term.getId(), eventPayload(term));
        outboxPublisher.publish(DomainEvents.MODELING_TERM_CREATED, term.getId().toString(), eventPayload(term));
        return toDTO(term, true);
    }

    @Transactional
    public BusinessTermDTO update(UUID id, BusinessTermRequest request) {
        BusinessTerm term = getTerm(id);
        if ("ARCHIVED".equalsIgnoreCase(term.getStatus())) {
            throw new BizException(40002, "归档术语不可编辑");
        }
        boolean wasApproved = "APPROVED".equalsIgnoreCase(term.getStatus());
        Map<String, Object> before = eventPayload(term);
        if (request.code() != null && !request.code().isBlank() && !request.code().equalsIgnoreCase(term.getCode())) {
            termRepo.findByTenantIdAndCodeIgnoreCase(term.getTenantId(), request.code().trim())
                .ifPresent(existing -> {
                    throw new BizException(40001, "术语编码已存在");
                });
            term.setCode(request.code().trim());
        }
        apply(term, request);
        if ("APPROVED".equalsIgnoreCase(term.getStatus())) {
            term.setStatus("REVIEWING");
        }
        term.setUpdatedBy(TenantContext.getUserId());
        term.setUpdatedAt(Instant.now());
        term = termRepo.save(term);
        auditLogger.auditUpdate("BUSINESS_TERM", term.getId(), eventPayload(term));
        outboxPublisher.publish(DomainEvents.MODELING_TERM_UPDATED, term.getId().toString(), eventPayload(term));
        if (wasApproved && governanceChanged(before, eventPayload(term))) {
            createGlossaryChangeApproval(term, before, eventPayload(term));
        }
        return toDTO(term, true);
    }

    @Transactional
    public BusinessTermDTO submit(UUID id) {
        BusinessTerm term = getTerm(id);
        requireReadyForApproval(term);
        term.setStatus("REVIEWING");
        term.setUpdatedBy(TenantContext.getUserId());
        term.setUpdatedAt(Instant.now());
        term = termRepo.save(term);
        auditLogger.audit("SUBMIT", "BUSINESS_TERM", term.getId().toString(), eventPayload(term));
        outboxPublisher.publish(DomainEvents.MODELING_TERM_UPDATED, term.getId().toString(), eventPayload(term));
        return toDTO(term, true);
    }

    @Transactional
    public BusinessTermDTO approve(UUID id, String comment) {
        BusinessTerm term = getTerm(id);
        requireReadyForApproval(term);
        if (term.getApprovedAt() != null && !"APPROVED".equalsIgnoreCase(term.getStatus())) {
            term.setVersion(term.getVersion() == null ? 1 : term.getVersion() + 1);
        }
        term.setStatus("APPROVED");
        term.setApprovedBy(TenantContext.getUserId());
        term.setApprovedAt(Instant.now());
        term.setUpdatedBy(TenantContext.getUserId());
        term.setUpdatedAt(Instant.now());
        term = termRepo.save(term);
        snapshot(term, comment);
        auditLogger.audit("APPROVE", "BUSINESS_TERM", term.getId().toString(), eventPayload(term));
        outboxPublisher.publish(DomainEvents.MODELING_TERM_APPROVED, term.getId().toString(), eventPayload(term));
        return toDTO(term, true);
    }

    @Transactional
    public BusinessTermDTO reject(UUID id, String comment) {
        BusinessTerm term = getTerm(id);
        term.setStatus("REJECTED");
        term.setUpdatedBy(TenantContext.getUserId());
        term.setUpdatedAt(Instant.now());
        term = termRepo.save(term);
        auditLogger.audit("REJECT", "BUSINESS_TERM", term.getId().toString(), Map.of(
            "termId", term.getId(),
            "comment", comment == null ? "" : comment
        ));
        outboxPublisher.publish(DomainEvents.MODELING_TERM_UPDATED, term.getId().toString(), eventPayload(term));
        return toDTO(term, true);
    }

    @Transactional
    public BusinessTermDTO deprecate(UUID id, String comment) {
        BusinessTerm term = getTerm(id);
        term.setStatus("DEPRECATED");
        term.setUpdatedBy(TenantContext.getUserId());
        term.setUpdatedAt(Instant.now());
        term = termRepo.save(term);
        auditLogger.audit("DEPRECATE", "BUSINESS_TERM", term.getId().toString(), Map.of(
            "termId", term.getId(),
            "comment", comment == null ? "" : comment
        ));
        outboxPublisher.publish(DomainEvents.MODELING_TERM_DEPRECATED, term.getId().toString(), eventPayload(term));
        return toDTO(term, true);
    }

    @Transactional(readOnly = true)
    public List<BusinessTermBindingDTO> bindings(UUID termId) {
        BusinessTerm term = getTerm(termId);
        return bindingRepo.findByTenantIdAndTermIdOrderByCreatedAtDesc(term.getTenantId(), term.getId()).stream()
            .map(binding -> toBindingDTO(binding, term))
            .toList();
    }

    @Transactional(readOnly = true)
    public List<BusinessTermBindingDTO> bindingsByAsset(String assetFqn) {
        validateRequired(assetFqn, "assetFqn 必填");
        UUID tenantId = TenantContext.getTenantId();
        return bindingRepo.findByTenantIdAndAssetFqnAndStatusOrderByColumnNameAsc(tenantId, assetFqn, "ACTIVE").stream()
            .map(binding -> toBindingDTO(binding, getTerm(binding.getTermId())))
            .toList();
    }

    @Transactional
    public BusinessTermBindingDTO bind(UUID termId, BusinessTermBindingRequest request) {
        BusinessTerm term = getTerm(termId);
        validateRequired(request.assetFqn(), "assetFqn 必填");
        String columnName = blankToNull(request.columnName());
        BusinessTermBinding binding = bindingRepo
            .findByTenantIdAndTermIdAndAssetFqnAndColumnName(term.getTenantId(), term.getId(), request.assetFqn(), columnName)
            .orElseGet(BusinessTermBinding::new);
        binding.setTenantId(term.getTenantId());
        binding.setTermId(term.getId());
        binding.setAssetId(request.assetId());
        binding.setAssetFqn(request.assetFqn().trim());
        binding.setColumnName(columnName);
        binding.setRelationType(defaultText(request.relationType(), "DEFINES").toUpperCase(Locale.ROOT));
        binding.setSource(defaultText(request.source(), "MANUAL").toUpperCase(Locale.ROOT));
        binding.setConfidence(request.confidence());
        binding.setStatus("ACTIVE");
        binding.setCreatedBy(binding.getCreatedBy() == null ? TenantContext.getUserId() : binding.getCreatedBy());
        binding.setUpdatedAt(Instant.now());
        binding = bindingRepo.save(binding);
        auditLogger.audit("BIND", "BUSINESS_TERM", term.getId().toString(), bindingPayload(term, binding));
        outboxPublisher.publish(DomainEvents.MODELING_TERM_BINDING_CHANGED, binding.getId().toString(), bindingPayload(term, binding));
        createPiiCandidateIfSensitive(term, binding);
        return toBindingDTO(binding, term);
    }

    @Transactional
    public BusinessTermBindingDTO markBindingStale(UUID bindingId) {
        UUID tenantId = TenantContext.getTenantId();
        BusinessTermBinding binding = bindingRepo.findByIdAndTenantId(bindingId, tenantId)
            .orElseThrow(() -> new BizException(40400, "术语绑定不存在"));
        BusinessTerm term = getTerm(binding.getTermId());
        binding.setStatus("STALE");
        binding.setUpdatedAt(Instant.now());
        binding = bindingRepo.save(binding);
        auditLogger.audit("UNBIND", "BUSINESS_TERM", term.getId().toString(), bindingPayload(term, binding));
        outboxPublisher.publish(DomainEvents.MODELING_TERM_BINDING_CHANGED, binding.getId().toString(), bindingPayload(term, binding));
        return toBindingDTO(binding, term);
    }

    @Transactional(readOnly = true)
    public List<BusinessTermVersionDTO> versions(UUID termId) {
        BusinessTerm term = getTerm(termId);
        return versionRepo.findByTenantIdAndTermIdOrderByVersionDesc(term.getTenantId(), term.getId()).stream()
            .map(version -> new BusinessTermVersionDTO(
                version.getId(),
                version.getTermId(),
                version.getVersion(),
                version.getSnapshot(),
                version.getChangeReason(),
                version.getChangedBy(),
                version.getCreatedAt()
            ))
            .toList();
    }

    @Transactional(readOnly = true)
    public BusinessTermImpactDTO impact(UUID termId) {
        BusinessTerm term = getTerm(termId);
        List<BusinessTermBindingDTO> activeBindings = bindings(termId).stream()
            .filter(binding -> "ACTIVE".equalsIgnoreCase(binding.status()))
            .toList();
        List<BusinessTermImpactDTO.AssetImpactDTO> assets = impactedAssets(term, activeBindings);
        List<BusinessTermImpactDTO.QualityRuleImpactDTO> qualityRules = impactedQualityRules(term, activeBindings);
        List<BusinessTermImpactDTO.ApiImpactDTO> apis = impactedApis(term, activeBindings);
        List<BusinessTermImpactDTO.DagImpactDTO> dags = impactedDags(term, activeBindings);
        List<BusinessTermImpactDTO.SecurityNoticeDTO> securityNotices = securityNotices(term, activeBindings);
        List<BusinessTermImpactDTO.ApprovalImpactDTO> approvals = approvalImpacts(term);
        List<String> warnings = impactWarnings(term, activeBindings, apis, securityNotices, approvals);
        int score = activeBindings.size()
            + assets.size() * 2
            + qualityRules.size() * 2
            + apis.size() * 3
            + dags.size() * 2
            + securityNotices.size() * 2
            + approvals.size();

        return new BusinessTermImpactDTO(
            term.getId(),
            term.getCode(),
            term.getName(),
            term.getStatus(),
            term.getVersion(),
            term.getSensitivityLevel(),
            activeBindings,
            assets,
            qualityRules,
            apis,
            dags,
            securityNotices,
            approvals,
            warnings,
            score
        );
    }

    @Transactional(readOnly = true)
    public BusinessTermVersionDiffDTO latestVersionDiff(UUID termId) {
        BusinessTerm term = getTerm(termId);
        List<BusinessTermVersion> versions = versionRepo.findByTenantIdAndTermIdOrderByVersionDesc(term.getTenantId(), term.getId());
        if (versions.isEmpty()) {
            return new BusinessTermVersionDiffDTO(term.getId(), null, null, Collections.emptyList());
        }
        BusinessTermVersion latest = versions.get(0);
        Map<String, Object> before;
        Integer fromVersion;
        Integer toVersion;
        if (versions.size() > 1) {
            BusinessTermVersion previous = versions.get(1);
            before = snapshotMap(previous.getSnapshot());
            fromVersion = previous.getVersion();
            toVersion = latest.getVersion();
            return new BusinessTermVersionDiffDTO(term.getId(), fromVersion, toVersion,
                changes(before, snapshotMap(latest.getSnapshot())));
        }
        before = snapshotMap(latest.getSnapshot());
        fromVersion = latest.getVersion();
        toVersion = term.getVersion();
        return new BusinessTermVersionDiffDTO(term.getId(), fromVersion, toVersion, changes(before, eventPayload(term)));
    }

    private List<BusinessTermImpactDTO.AssetImpactDTO> impactedAssets(
        BusinessTerm term,
        List<BusinessTermBindingDTO> activeBindings
    ) {
        Map<String, BusinessTermImpactDTO.AssetImpactDTO> result = new LinkedHashMap<>();
        for (BusinessTermBindingDTO binding : activeBindings) {
            String assetFqn = binding.assetFqn();
            if (assetFqn == null || assetFqn.isBlank()) {
                continue;
            }
            for (Map<String, Object> row : queryForList("""
                SELECT id, om_fqn AS fqn, COALESCE(display_name, om_fqn) AS display_name, layer
                FROM catalog.asset
                WHERE tenant_id = ? AND om_fqn = ?
                """, term.getTenantId(), assetFqn)) {
                result.put(assetKey("BOUND", text(row.get("fqn"))), new BusinessTermImpactDTO.AssetImpactDTO(
                    uuid(row.get("id")),
                    text(row.get("fqn")),
                    text(row.get("display_name")),
                    text(row.get("layer")),
                    "BOUND"
                ));
            }
            result.putIfAbsent(assetKey("BOUND", assetFqn), new BusinessTermImpactDTO.AssetImpactDTO(
                binding.assetId(),
                assetFqn,
                assetFqn,
                null,
                "BOUND"
            ));
            for (Map<String, Object> row : queryForList("""
                SELECT a.id,
                       le.downstream_fqn AS fqn,
                       COALESCE(a.display_name, le.downstream_fqn) AS display_name,
                       a.layer
                FROM catalog.lineage_edge le
                LEFT JOIN catalog.asset a ON a.tenant_id = le.tenant_id AND a.om_fqn = le.downstream_fqn
                WHERE le.tenant_id = ? AND le.upstream_fqn = ?
                ORDER BY le.synced_at DESC
                LIMIT 50
                """, term.getTenantId(), assetFqn)) {
                String fqn = text(row.get("fqn"));
                result.put(assetKey("DOWNSTREAM", fqn), new BusinessTermImpactDTO.AssetImpactDTO(
                    uuid(row.get("id")),
                    fqn,
                    text(row.get("display_name")),
                    text(row.get("layer")),
                    "DOWNSTREAM"
                ));
            }
        }
        return new ArrayList<>(result.values());
    }

    private List<BusinessTermImpactDTO.QualityRuleImpactDTO> impactedQualityRules(
        BusinessTerm term,
        List<BusinessTermBindingDTO> activeBindings
    ) {
        Map<UUID, BusinessTermImpactDTO.QualityRuleImpactDTO> result = new LinkedHashMap<>();
        for (BusinessTermBindingDTO binding : activeBindings) {
            for (Map<String, Object> row : queryForList("""
                SELECT id, target_fqn, target_column, rule_type, severity, enabled
                FROM quality.rule
                WHERE tenant_id = ?
                  AND target_fqn = ?
                  AND (? IS NULL OR target_column IS NULL OR target_column = ?)
                ORDER BY created_at DESC
                LIMIT 50
                """, term.getTenantId(), binding.assetFqn(), binding.columnName(), binding.columnName())) {
                UUID id = uuid(row.get("id"));
                if (id == null) {
                    continue;
                }
                result.put(id, new BusinessTermImpactDTO.QualityRuleImpactDTO(
                    id,
                    text(row.get("target_fqn")),
                    text(row.get("target_column")),
                    text(row.get("rule_type")),
                    text(row.get("severity")),
                    bool(row.get("enabled"))
                ));
            }
        }
        return new ArrayList<>(result.values());
    }

    private List<BusinessTermImpactDTO.ApiImpactDTO> impactedApis(
        BusinessTerm term,
        List<BusinessTermBindingDTO> activeBindings
    ) {
        Map<UUID, BusinessTermImpactDTO.ApiImpactDTO> result = new LinkedHashMap<>();
        Set<String> probes = new LinkedHashSet<>();
        probes.add(term.getCode());
        probes.add(term.getName());
        for (BusinessTermBindingDTO binding : activeBindings) {
            probes.add(binding.assetFqn());
            if (binding.columnName() != null) {
                probes.add(binding.columnName());
            }
        }
        for (String probe : probes) {
            if (probe == null || probe.isBlank()) {
                continue;
            }
            String like = "%" + probe.toLowerCase(Locale.ROOT) + "%";
            for (Map<String, Object> row : queryForList("""
                SELECT id, api_path, source_fqn, status
                FROM dataservice.api_definition
                WHERE tenant_id = ?
                  AND (
                    lower(coalesce(source_fqn, '')) = lower(?)
                    OR lower(select_sql) LIKE ?
                    OR lower(coalesce(response_schema::text, '')) LIKE ?
                  )
                ORDER BY created_at DESC
                LIMIT 50
                """, term.getTenantId(), probe, like, like)) {
                UUID id = uuid(row.get("id"));
                if (id == null) {
                    continue;
                }
                result.put(id, new BusinessTermImpactDTO.ApiImpactDTO(
                    id,
                    text(row.get("api_path")),
                    text(row.get("source_fqn")),
                    text(row.get("status"))
                ));
            }
        }
        return new ArrayList<>(result.values());
    }

    private List<BusinessTermImpactDTO.DagImpactDTO> impactedDags(
        BusinessTerm term,
        List<BusinessTermBindingDTO> activeBindings
    ) {
        Map<UUID, BusinessTermImpactDTO.DagImpactDTO> result = new LinkedHashMap<>();
        Set<String> probes = new LinkedHashSet<>();
        probes.add(term.getCode());
        activeBindings.forEach(binding -> probes.add(binding.assetFqn()));
        for (String probe : probes) {
            if (probe == null || probe.isBlank()) {
                continue;
            }
            for (Map<String, Object> row : queryForList("""
                SELECT id, name, dagster_job, enabled
                FROM orchestration.dag
                WHERE tenant_id = ? AND lower(definition::text) LIKE ?
                ORDER BY created_at DESC
                LIMIT 50
                """, term.getTenantId(), "%" + probe.toLowerCase(Locale.ROOT) + "%")) {
                UUID id = uuid(row.get("id"));
                if (id == null) {
                    continue;
                }
                result.put(id, new BusinessTermImpactDTO.DagImpactDTO(
                    id,
                    text(row.get("name")),
                    text(row.get("dagster_job")),
                    bool(row.get("enabled"))
                ));
            }
        }
        return new ArrayList<>(result.values());
    }

    private List<BusinessTermImpactDTO.SecurityNoticeDTO> securityNotices(
        BusinessTerm term,
        List<BusinessTermBindingDTO> activeBindings
    ) {
        Map<String, BusinessTermImpactDTO.SecurityNoticeDTO> result = new LinkedHashMap<>();
        for (BusinessTermBindingDTO binding : activeBindings) {
            String fqn = binding.columnName() == null ? binding.assetFqn() : binding.assetFqn() + "." + binding.columnName();
            for (Map<String, Object> row : queryForList("""
                SELECT fqn, pii_type, suggest_level, status
                FROM security.pii_scan_record
                WHERE tenant_id = ?
                  AND (fqn = ? OR (? IS NULL AND fqn LIKE ?))
                ORDER BY scanned_at DESC
                LIMIT 50
                """, term.getTenantId(), fqn, binding.columnName(), binding.assetFqn() + ".%")) {
                String rowFqn = text(row.get("fqn"));
                result.put("PII:" + rowFqn, new BusinessTermImpactDTO.SecurityNoticeDTO(
                    "PII_SCAN",
                    rowFqn,
                    text(row.get("suggest_level")),
                    text(row.get("status")),
                    "敏感字段待安全确认：" + text(row.get("pii_type"))
                ));
            }
        }
        if (isSensitive(term.getSensitivityLevel()) && result.isEmpty() && !activeBindings.isEmpty()) {
            result.put("SENSITIVE_TERM", new BusinessTermImpactDTO.SecurityNoticeDTO(
                "SENSITIVE_TERM",
                term.getCode(),
                term.getSensitivityLevel(),
                "PENDING",
                "术语密级为 " + term.getSensitivityLevel() + "，建议确认绑定字段的脱敏策略"
            ));
        }
        return new ArrayList<>(result.values());
    }

    private List<BusinessTermImpactDTO.ApprovalImpactDTO> approvalImpacts(BusinessTerm term) {
        String targetRef = glossaryTargetRef(term.getId());
        return queryForList("""
            SELECT id, request_type, target_ref, status, created_at
            FROM security.approval_request
            WHERE tenant_id = ?
              AND (target_ref = ? OR lower(coalesce(payload::text, '')) LIKE ?)
            ORDER BY created_at DESC
            LIMIT 20
            """, term.getTenantId(), targetRef, "%" + term.getCode().toLowerCase(Locale.ROOT) + "%")
            .stream()
            .map(row -> new BusinessTermImpactDTO.ApprovalImpactDTO(
                uuid(row.get("id")),
                text(row.get("request_type")),
                text(row.get("target_ref")),
                text(row.get("status")),
                instant(row.get("created_at"))
            ))
            .filter(row -> row.id() != null)
            .toList();
    }

    private List<String> impactWarnings(
        BusinessTerm term,
        List<BusinessTermBindingDTO> activeBindings,
        List<BusinessTermImpactDTO.ApiImpactDTO> apis,
        List<BusinessTermImpactDTO.SecurityNoticeDTO> securityNotices,
        List<BusinessTermImpactDTO.ApprovalImpactDTO> approvals
    ) {
        List<String> warnings = new ArrayList<>();
        if (activeBindings.isEmpty()) {
            warnings.add("术语尚未绑定资产字段，目录检索和下游继承不会命中该术语");
        }
        if ("DEPRECATED".equalsIgnoreCase(term.getStatus()) && !activeBindings.isEmpty()) {
            warnings.add("术语已废弃但仍存在活跃字段绑定，建议迁移或标记绑定失效");
        }
        if (isSensitive(term.getSensitivityLevel()) && securityNotices.stream().noneMatch(item -> "PII_SCAN".equals(item.type()))) {
            warnings.add("敏感术语尚未形成 PII 扫描记录，建议补充安全确认");
        }
        if (!apis.isEmpty()) {
            warnings.add("术语变更会影响已继承该字段定义的 DaaS API 文档或响应契约");
        }
        if (approvals.stream().anyMatch(item -> "PENDING".equalsIgnoreCase(item.status()))) {
            warnings.add("存在待处理治理审批，审定前需确认影响面");
        }
        return warnings;
    }

    private void createGlossaryChangeApproval(
        BusinessTerm term,
        Map<String, Object> before,
        Map<String, Object> after
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("termId", term.getId());
        payload.put("termCode", term.getCode());
        payload.put("termName", term.getName());
        payload.put("before", before);
        payload.put("after", after);
        payload.put("changes", changes(before, after));
        try {
            String targetRef = glossaryTargetRef(term.getId());
            jdbc.update("""
                INSERT INTO security.approval_request
                    (tenant_id, request_type, applicant_id, target_ref, payload, status, created_at)
                SELECT ?, 'GLOSSARY_CHANGE', ?, ?, ?::jsonb, 'PENDING', now()
                WHERE NOT EXISTS (
                    SELECT 1
                    FROM security.approval_request
                    WHERE tenant_id = ?
                      AND request_type = 'GLOSSARY_CHANGE'
                      AND target_ref = ?
                      AND status = 'PENDING'
                )
                """,
                term.getTenantId(),
                TenantContext.getUserId(),
                targetRef,
                JsonUtil.toJson(payload),
                term.getTenantId(),
                targetRef
            );
        } catch (DataAccessException ignored) {
            // Security schema may be disabled in lightweight local profiles.
        }
    }

    private void createPiiCandidateIfSensitive(BusinessTerm term, BusinessTermBinding binding) {
        if (!isSensitive(term.getSensitivityLevel()) || binding.getColumnName() == null || binding.getColumnName().isBlank()) {
            return;
        }
        String fqn = binding.getAssetFqn() + "." + binding.getColumnName();
        try {
            jdbc.update("""
                INSERT INTO security.pii_scan_record
                    (tenant_id, fqn, pii_type, confidence, suggest_level, status, scanned_at)
                VALUES (?, ?, ?, ?, ?, 'PENDING', now())
                ON CONFLICT (tenant_id, fqn)
                DO UPDATE SET
                    pii_type = EXCLUDED.pii_type,
                    confidence = GREATEST(security.pii_scan_record.confidence, EXCLUDED.confidence),
                    suggest_level = EXCLUDED.suggest_level,
                    status = CASE
                        WHEN security.pii_scan_record.status = 'IGNORED' THEN 'PENDING'
                        ELSE security.pii_scan_record.status
                    END,
                    scanned_at = now()
                """,
                term.getTenantId(),
                fqn,
                limit(term.getName(), 32),
                0.86d,
                term.getSensitivityLevel()
            );
        } catch (DataAccessException ignored) {
            // Security schema may be disabled in lightweight local profiles.
        }
    }

    private boolean governanceChanged(Map<String, Object> before, Map<String, Object> after) {
        return List.of(
            "termCode",
            "termName",
            "domainId",
            "definition",
            "caliberSql",
            "synonyms",
            "ownerName",
            "sensitivityLevel",
            "tags"
        ).stream().anyMatch(field -> !Objects.equals(before.get(field), after.get(field)));
    }

    private List<BusinessTermVersionDiffDTO.FieldChangeDTO> changes(
        Map<String, Object> before,
        Map<String, Object> after
    ) {
        List<String> fields = List.of(
            "termCode",
            "termName",
            "domainId",
            "definition",
            "caliberSql",
            "synonyms",
            "ownerName",
            "sensitivityLevel",
            "tags",
            "status"
        );
        return fields.stream()
            .filter(field -> !Objects.equals(before.get(field), after.get(field)))
            .map(field -> new BusinessTermVersionDiffDTO.FieldChangeDTO(field, before.get(field), after.get(field)))
            .toList();
    }

    private Map<String, Object> snapshotMap(String raw) {
        if (raw == null || raw.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return JsonUtil.mapper().convertValue(
                JsonUtil.parse(raw),
                JsonUtil.mapper().getTypeFactory().constructMapType(Map.class, String.class, Object.class)
            );
        } catch (Exception ignored) {
            return Collections.emptyMap();
        }
    }

    private List<Map<String, Object>> queryForList(String sql, Object... args) {
        try {
            return jdbc.queryForList(sql, args);
        } catch (DataAccessException ignored) {
            return Collections.emptyList();
        }
    }

    private String assetKey(String relation, String fqn) {
        return relation + ":" + fqn;
    }

    private boolean isSensitive(String level) {
        return "L3".equalsIgnoreCase(level) || "L4".equalsIgnoreCase(level);
    }

    private String glossaryTargetRef(UUID termId) {
        return "business_term:" + termId;
    }

    private UUID uuid(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof UUID id) {
            return id;
        }
        return UUID.fromString(String.valueOf(value));
    }

    private Boolean bool(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private Instant instant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof java.time.OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        if (value instanceof java.time.LocalDateTime localDateTime) {
            return localDateTime.atZone(java.time.ZoneId.systemDefault()).toInstant();
        }
        return Instant.parse(String.valueOf(value));
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private BusinessTerm getTerm(UUID id) {
        return termRepo.findByIdAndTenantId(id, TenantContext.getTenantId())
            .orElseThrow(() -> new BizException(40400, "业务术语不存在"));
    }

    private void apply(BusinessTerm term, BusinessTermRequest request) {
        if (request.name() != null && !request.name().isBlank()) term.setName(request.name().trim());
        term.setDomainId(request.domainId());
        term.setDefinition(request.definition());
        term.setCaliberSql(request.caliberSql());
        term.setSynonyms(JsonUtil.toJson(request.synonyms() == null ? Collections.emptyList() : request.synonyms()));
        term.setOwnerId(request.ownerId());
        term.setOwnerName(request.ownerName());
        term.setStewardId(request.stewardId());
        term.setSensitivityLevel(request.sensitivityLevel());
        term.setTags(JsonUtil.toJson(request.tags() == null ? Collections.emptyList() : request.tags()));
    }

    private BusinessTermDTO toDTO(BusinessTerm term, boolean includeBindings) {
        String domainName = null;
        if (term.getDomainId() != null) {
            domainName = domainRepo.findById(term.getDomainId()).map(SubjectDomain::getName).orElse(null);
        }
        List<BusinessTermBindingDTO> bindings = includeBindings
            ? bindingRepo.findByTenantIdAndTermIdOrderByCreatedAtDesc(term.getTenantId(), term.getId()).stream()
                .map(binding -> toBindingDTO(binding, term))
                .toList()
            : Collections.emptyList();
        long bindingCount = includeBindings
            ? bindings.stream().filter(binding -> "ACTIVE".equalsIgnoreCase(binding.status())).count()
            : bindingRepo.countByTenantIdAndTermIdAndStatus(term.getTenantId(), term.getId(), "ACTIVE");
        return new BusinessTermDTO(
            term.getId(),
            term.getCode(),
            term.getName(),
            term.getDomainId(),
            domainName,
            term.getDefinition(),
            term.getCaliberSql(),
            stringList(term.getSynonyms()),
            term.getOwnerId(),
            term.getOwnerName(),
            term.getStewardId(),
            term.getStatus(),
            term.getVersion(),
            term.getSensitivityLevel(),
            stringList(term.getTags()),
            term.getCreatedAt(),
            term.getUpdatedAt(),
            term.getApprovedAt(),
            bindingCount,
            bindings
        );
    }

    private BusinessTermBindingDTO toBindingDTO(BusinessTermBinding binding, BusinessTerm term) {
        return new BusinessTermBindingDTO(
            binding.getId(),
            binding.getTermId(),
            term.getCode(),
            term.getName(),
            binding.getAssetId(),
            binding.getAssetFqn(),
            binding.getColumnName(),
            binding.getRelationType(),
            binding.getSource(),
            binding.getConfidence(),
            binding.getStatus(),
            binding.getCreatedAt(),
            binding.getUpdatedAt()
        );
    }

    private void snapshot(BusinessTerm term, String changeReason) {
        BusinessTermVersion version = new BusinessTermVersion();
        version.setTenantId(term.getTenantId());
        version.setTermId(term.getId());
        version.setVersion(term.getVersion());
        version.setSnapshot(JsonUtil.toJson(eventPayload(term)));
        version.setChangeReason(changeReason);
        version.setChangedBy(TenantContext.getUserId());
        versionRepo.save(version);
    }

    private Map<String, Object> eventPayload(BusinessTerm term) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tenantId", term.getTenantId());
        payload.put("termId", term.getId());
        payload.put("termCode", term.getCode());
        payload.put("termName", term.getName());
        payload.put("domainId", term.getDomainId());
        payload.put("definition", term.getDefinition());
        payload.put("caliberSql", term.getCaliberSql());
        payload.put("ownerId", term.getOwnerId());
        payload.put("ownerName", term.getOwnerName());
        payload.put("stewardId", term.getStewardId());
        payload.put("status", term.getStatus());
        payload.put("version", term.getVersion());
        payload.put("sensitivityLevel", term.getSensitivityLevel());
        payload.put("synonyms", stringList(term.getSynonyms()));
        payload.put("tags", stringList(term.getTags()));
        payload.put("updatedAt", term.getUpdatedAt());
        return payload;
    }

    private Map<String, Object> bindingPayload(BusinessTerm term, BusinessTermBinding binding) {
        Map<String, Object> payload = eventPayload(term);
        payload.put("bindingId", binding.getId());
        payload.put("assetId", binding.getAssetId());
        payload.put("assetFqn", binding.getAssetFqn());
        payload.put("columnName", binding.getColumnName());
        payload.put("relationType", binding.getRelationType());
        payload.put("bindingSource", binding.getSource());
        payload.put("bindingStatus", binding.getStatus());
        payload.put("changedBy", TenantContext.getUserId());
        payload.put("changedAt", binding.getUpdatedAt());
        return payload;
    }

    private boolean matches(BusinessTerm term, String q) {
        return contains(term.getCode(), q)
            || contains(term.getName(), q)
            || contains(term.getDefinition(), q)
            || contains(term.getCaliberSql(), q)
            || stringList(term.getSynonyms()).stream().anyMatch(s -> contains(s, q));
    }

    private boolean contains(String value, String q) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(q);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private List<String> stringList(String raw) {
        if (raw == null || raw.isBlank()) return Collections.emptyList();
        try {
            JsonNode node = JsonUtil.parse(raw);
            if (!node.isArray()) return Collections.emptyList();
            return JsonUtil.mapper().convertValue(node, JsonUtil.mapper().getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    private void requireReadyForApproval(BusinessTerm term) {
        validateRequired(term.getDefinition(), "审定前必须填写术语定义");
        validateRequired(term.getCaliberSql(), "审定前必须填写口径");
    }

    private void validateRequired(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BizException(40001, message);
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String limit(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
