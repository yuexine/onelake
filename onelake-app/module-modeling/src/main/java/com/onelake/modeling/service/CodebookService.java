package com.onelake.modeling.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.onelake.common.audit.AuditLogger;
import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.outbox.DomainEvents;
import com.onelake.common.outbox.OutboxPublisher;
import com.onelake.common.util.JsonUtil;
import com.onelake.modeling.domain.entity.Codebook;
import com.onelake.modeling.domain.entity.CodebookVersion;
import com.onelake.modeling.dto.CodebookDTO;
import com.onelake.modeling.dto.CodebookEntryDTO;
import com.onelake.modeling.dto.CodebookPublishRequest;
import com.onelake.modeling.dto.CodebookRequest;
import com.onelake.modeling.dto.CodebookVersionDTO;
import com.onelake.modeling.repository.CodebookRepository;
import com.onelake.modeling.repository.CodebookVersionRepository;
import lombok.RequiredArgsConstructor;
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
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CodebookService {

    private final CodebookRepository codebookRepo;
    private final CodebookVersionRepository versionRepo;
    private final OutboxPublisher outboxPublisher;
    private final AuditLogger auditLogger;

    @Transactional(readOnly = true)
    public List<CodebookDTO> list(String keyword, String status, String domain) {
        UUID tenantId = TenantContext.getTenantId();
        String q = normalize(keyword);
        String s = normalizeStatus(status);
        String d = normalize(domain);
        return codebookRepo.findByTenantIdOrderByUpdatedAtDesc(tenantId).stream()
            .filter(item -> s == null || s.equalsIgnoreCase(item.getStatus()))
            .filter(item -> d.isBlank() || d.equals(normalize(item.getDomain())))
            .filter(item -> q.isBlank() || matches(item, q))
            .map(this::toDTO)
            .toList();
    }

    @Transactional(readOnly = true)
    public CodebookDTO get(UUID id) {
        return toDTO(getCodebook(id));
    }

    @Transactional
    public CodebookDTO create(CodebookRequest request) {
        validateRequired(request.code(), "code 必填");
        validateRequired(request.name(), "name 必填");
        UUID tenantId = TenantContext.getTenantId();
        codebookRepo.findByTenantIdAndCodeIgnoreCase(tenantId, request.code().trim())
            .ifPresent(existing -> {
                throw new BizException(40001, "字典编码已存在");
            });

        Codebook codebook = new Codebook();
        codebook.setTenantId(tenantId);
        codebook.setCode(request.code().trim());
        apply(codebook, request);
        codebook.setStatus("DRAFT");
        codebook.setCreatedBy(TenantContext.getUserId());
        codebook.setUpdatedBy(TenantContext.getUserId());
        codebook = codebookRepo.save(codebook);
        auditLogger.auditCreate("CODEBOOK", codebook.getId(), eventPayload(codebook));
        outboxPublisher.publish(DomainEvents.MODELING_CODEBOOK_CREATED, codebook.getId().toString(), eventPayload(codebook));
        return toDTO(codebook);
    }

    @Transactional
    public CodebookDTO update(UUID id, CodebookRequest request) {
        Codebook codebook = getCodebook(id);
        if ("ARCHIVED".equalsIgnoreCase(codebook.getStatus())) {
            throw new BizException(40002, "归档字典不可编辑");
        }
        if (request.code() != null && !request.code().isBlank() && !request.code().trim().equalsIgnoreCase(codebook.getCode())) {
            codebookRepo.findByTenantIdAndCodeIgnoreCase(codebook.getTenantId(), request.code().trim())
                .ifPresent(existing -> {
                    throw new BizException(40001, "字典编码已存在");
                });
            codebook.setCode(request.code().trim());
        }
        apply(codebook, request);
        if ("PUBLISHED".equalsIgnoreCase(codebook.getStatus())) {
            codebook.setStatus("DRAFT");
        }
        codebook.setUpdatedBy(TenantContext.getUserId());
        codebook.setUpdatedAt(Instant.now());
        codebook = codebookRepo.save(codebook);
        auditLogger.auditUpdate("CODEBOOK", codebook.getId(), eventPayload(codebook));
        outboxPublisher.publish(DomainEvents.MODELING_CODEBOOK_UPDATED, codebook.getId().toString(), eventPayload(codebook));
        return toDTO(codebook);
    }

    @Transactional
    public CodebookDTO publish(UUID id, CodebookPublishRequest request) {
        Codebook codebook = getCodebook(id);
        List<CodebookEntryDTO> entries = entryList(codebook.getEntries());
        if (entries.isEmpty()) {
            throw new BizException(40003, "字典项不能为空");
        }
        String version = request == null || request.version() == null || request.version().isBlank()
            ? nextVersion(codebook)
            : request.version().trim();
        versionRepo.findByTenantIdAndCodebookIdAndVersionIgnoreCase(codebook.getTenantId(), codebook.getId(), version)
            .ifPresent(existing -> {
                throw new BizException(40004, "字典版本已存在");
            });

        codebook.setStatus("PUBLISHED");
        codebook.setLatestVersion(version);
        codebook.setPublishedBy(TenantContext.getUserId());
        codebook.setPublishedAt(Instant.now());
        codebook.setUpdatedBy(TenantContext.getUserId());
        codebook.setUpdatedAt(Instant.now());
        codebook = codebookRepo.save(codebook);

        CodebookVersion snapshot = new CodebookVersion();
        snapshot.setTenantId(codebook.getTenantId());
        snapshot.setCodebookId(codebook.getId());
        snapshot.setVersion(version);
        snapshot.setEntries(codebook.getEntries());
        snapshot.setSnapshot(JsonUtil.toJson(eventPayload(codebook)));
        snapshot.setChangeReason(request == null ? null : request.comment());
        snapshot.setPublishedBy(TenantContext.getUserId());
        versionRepo.save(snapshot);

        auditLogger.audit("PUBLISH", "CODEBOOK", codebook.getId().toString(), eventPayload(codebook));
        outboxPublisher.publish(DomainEvents.MODELING_CODEBOOK_PUBLISHED, codebook.getId().toString(), eventPayload(codebook));
        return toDTO(codebook);
    }

    @Transactional
    public CodebookDTO deprecate(UUID id, String comment) {
        Codebook codebook = getCodebook(id);
        codebook.setStatus("DEPRECATED");
        codebook.setUpdatedBy(TenantContext.getUserId());
        codebook.setUpdatedAt(Instant.now());
        codebook = codebookRepo.save(codebook);
        Map<String, Object> payload = eventPayload(codebook);
        payload.put("comment", comment);
        auditLogger.audit("DEPRECATE", "CODEBOOK", codebook.getId().toString(), payload);
        outboxPublisher.publish(DomainEvents.MODELING_CODEBOOK_DEPRECATED, codebook.getId().toString(), payload);
        return toDTO(codebook);
    }

    @Transactional(readOnly = true)
    public List<CodebookVersionDTO> versions(UUID id) {
        Codebook codebook = getCodebook(id);
        return versionRepo.findByTenantIdAndCodebookIdOrderByCreatedAtDesc(codebook.getTenantId(), codebook.getId()).stream()
            .map(version -> new CodebookVersionDTO(
                version.getId(),
                version.getCodebookId(),
                version.getVersion(),
                entryList(version.getEntries()),
                version.getChangeReason(),
                version.getPublishedBy(),
                version.getCreatedAt()
            ))
            .toList();
    }

    private void apply(Codebook codebook, CodebookRequest request) {
        codebook.setName(request.name() == null ? codebook.getName() : request.name().trim());
        codebook.setDomain(blankToNull(request.domain()));
        codebook.setDescription(blankToNull(request.description()));
        codebook.setNoMatchPolicy(normalizeNoMatchPolicy(request.noMatchPolicy()));
        codebook.setEntries(JsonUtil.toJson(validateEntries(request.entries())));
        codebook.setTags(JsonUtil.toJson(request.tags() == null ? Collections.emptyList() : request.tags()));
    }

    private CodebookDTO toDTO(Codebook codebook) {
        return new CodebookDTO(
            codebook.getId(),
            codebook.getCode(),
            codebook.getName(),
            codebook.getDomain(),
            codebook.getDescription(),
            codebook.getStatus(),
            codebook.getLatestVersion(),
            codebook.getNoMatchPolicy(),
            entryList(codebook.getEntries()),
            stringList(codebook.getTags()),
            codebook.getCreatedAt(),
            codebook.getUpdatedAt(),
            codebook.getPublishedAt()
        );
    }

    private Map<String, Object> eventPayload(Codebook codebook) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tenantId", codebook.getTenantId());
        payload.put("codebookId", codebook.getId());
        payload.put("codebookCode", codebook.getCode());
        payload.put("codebookName", codebook.getName());
        payload.put("domain", codebook.getDomain());
        payload.put("status", codebook.getStatus());
        payload.put("latestVersion", codebook.getLatestVersion());
        payload.put("noMatchPolicy", codebook.getNoMatchPolicy());
        payload.put("entries", entryList(codebook.getEntries()));
        payload.put("tags", stringList(codebook.getTags()));
        payload.put("updatedAt", codebook.getUpdatedAt());
        return payload;
    }

    private List<CodebookEntryDTO> validateEntries(List<CodebookEntryDTO> entries) {
        if (entries == null || entries.isEmpty()) return Collections.emptyList();
        Set<String> seen = new LinkedHashSet<>();
        List<CodebookEntryDTO> normalized = new ArrayList<>();
        for (CodebookEntryDTO entry : entries) {
            validateRequired(entry == null ? null : entry.from(), "字典项原值必填");
            validateRequired(entry.to(), "字典项目标值必填");
            String from = entry.from().trim();
            if (!seen.add(from.toLowerCase(Locale.ROOT))) {
                throw new BizException(40005, "字典项原值重复: " + from);
            }
            normalized.add(new CodebookEntryDTO(from, entry.to().trim(), blankToNull(entry.label())));
        }
        return normalized;
    }

    private List<CodebookEntryDTO> entryList(String raw) {
        if (raw == null || raw.isBlank()) return Collections.emptyList();
        try {
            JsonNode node = JsonUtil.parse(raw);
            if (!node.isArray()) return Collections.emptyList();
            return JsonUtil.mapper().convertValue(
                node,
                JsonUtil.mapper().getTypeFactory().constructCollectionType(List.class, CodebookEntryDTO.class)
            );
        } catch (RuntimeException ignored) {
            return Collections.emptyList();
        }
    }

    private List<String> stringList(String raw) {
        if (raw == null || raw.isBlank()) return Collections.emptyList();
        try {
            JsonNode node = JsonUtil.parse(raw);
            if (!node.isArray()) return Collections.emptyList();
            return JsonUtil.mapper().convertValue(
                node,
                JsonUtil.mapper().getTypeFactory().constructCollectionType(List.class, String.class)
            );
        } catch (RuntimeException ignored) {
            return Collections.emptyList();
        }
    }

    private Codebook getCodebook(UUID id) {
        return codebookRepo.findByIdAndTenantId(id, TenantContext.getTenantId())
            .orElseThrow(() -> new BizException(40404, "字典不存在"));
    }

    private String nextVersion(Codebook codebook) {
        long next = versionRepo.countByTenantIdAndCodebookId(codebook.getTenantId(), codebook.getId()) + 1;
        return "v" + next;
    }

    private boolean matches(Codebook codebook, String q) {
        return contains(codebook.getCode(), q)
            || contains(codebook.getName(), q)
            || contains(codebook.getDomain(), q)
            || contains(codebook.getDescription(), q)
            || stringList(codebook.getTags()).stream().anyMatch(tag -> contains(tag, q));
    }

    private boolean contains(String value, String q) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(q);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeStatus(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeNoMatchPolicy(String value) {
        if (value == null || value.isBlank()) return "KEEP";
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!List.of("KEEP", "NULL", "FAIL").contains(normalized)) {
            throw new BizException(40006, "未命中策略仅支持 KEEP/NULL/FAIL");
        }
        return normalized;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void validateRequired(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BizException(40001, message);
        }
    }
}
