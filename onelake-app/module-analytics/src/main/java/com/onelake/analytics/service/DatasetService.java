package com.onelake.analytics.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.onelake.analytics.api.vo.DatasetRequest;
import com.onelake.analytics.domain.entity.Dataset;
import com.onelake.analytics.domain.enums.SourceType;
import com.onelake.analytics.dto.DatasetDTO;
import com.onelake.analytics.dto.DatasetDTO.FieldSchema;
import com.onelake.analytics.repository.DatasetRepository;
import com.onelake.common.audit.AuditLogger;
import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 数据集 CRUD 服务。
 * 不直接暴露 entity；DTO 转换在 service 内完成。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatasetService {

    private final DatasetRepository repo;
    private final AuditLogger audit;

    @Transactional
    public DatasetDTO create(DatasetRequest req) {
        UUID tenant = TenantContext.getTenantId();
        if (repo.existsByTenantIdAndName(tenant, req.getName())) {
            throw new BizException(40901, "数据集名重复：" + req.getName());
        }
        Dataset d = new Dataset();
        d.setTenantId(tenant);
        d.setName(req.getName());
        d.setSourceType(req.getSourceType());
        d.setAssetFqn(req.getAssetFqn());
        d.setSelectSql(req.getSelectSql());
        if (req.getApiId() != null) {
            d.setApiId(UUID.fromString(req.getApiId()));
        }
        if (req.getFieldSchema() != null) {
            d.setFieldSchema(JsonUtil.toJson(req.getFieldSchema()));
        }
        d.setClassification(req.getClassification() == null ? "L1" : req.getClassification());
        d.setCacheTtlSec(req.getCacheTtlSec() == null ? 300 : req.getCacheTtlSec());
        d.setRowFilter(req.getRowFilter());
        d.setCreatedBy(TenantContext.getUserId());
        repo.save(d);
        audit.auditCreate("analytics.dataset", d.getId(), req.getName());
        return toDto(d);
    }

    @Transactional
    public DatasetDTO update(UUID id, DatasetRequest req) {
        UUID tenant = TenantContext.getTenantId();
        Dataset d = repo.findByIdAndTenantId(id, tenant)
            .orElseThrow(() -> new BizException(40400, "数据集不存在"));
        if (req.getAssetFqn() != null) d.setAssetFqn(req.getAssetFqn());
        if (req.getSelectSql() != null) d.setSelectSql(req.getSelectSql());
        if (req.getSourceType() != null) d.setSourceType(req.getSourceType());
        if (req.getApiId() != null) d.setApiId(UUID.fromString(req.getApiId()));
        if (req.getFieldSchema() != null) d.setFieldSchema(JsonUtil.toJson(req.getFieldSchema()));
        if (req.getClassification() != null) d.setClassification(req.getClassification());
        if (req.getCacheTtlSec() != null) d.setCacheTtlSec(req.getCacheTtlSec());
        if (req.getRowFilter() != null) d.setRowFilter(req.getRowFilter());
        d.setUpdatedAt(Instant.now());
        repo.save(d);
        audit.auditUpdate("analytics.dataset", id, req);
        return toDto(d);
    }

    @Transactional
    public void delete(UUID id) {
        UUID tenant = TenantContext.getTenantId();
        Dataset d = repo.findByIdAndTenantId(id, tenant)
            .orElseThrow(() -> new BizException(40400, "数据集不存在"));
        repo.delete(d);
        audit.auditDelete("analytics.dataset", id);
    }

    @Transactional(readOnly = true)
    public DatasetDTO get(UUID id) {
        UUID tenant = TenantContext.getTenantId();
        return toDto(repo.findByIdAndTenantId(id, tenant)
            .orElseThrow(() -> new BizException(40400, "数据集不存在")));
    }

    /**
     * 内部调用（DatasetQueryService 用，跨服务时直接拿 entity）。
     */
    @Transactional(readOnly = true)
    public Dataset getEntity(UUID id, UUID tenantId) {
        return repo.findByIdAndTenantId(id, tenantId)
            .orElseThrow(() -> new BizException(40400, "数据集不存在"));
    }

    @Transactional(readOnly = true)
    public List<DatasetDTO> list() {
        UUID tenant = TenantContext.getTenantId();
        return repo.findByTenantId(tenant).stream().map(this::toDto).toList();
    }

    private DatasetDTO toDto(Dataset d) {
        DatasetDTO dto = DatasetDTO.from(d);
        if (d.getFieldSchema() != null && !d.getFieldSchema().isBlank()) {
            try {
                dto.setFieldSchema(
                    JsonUtil.mapper().readValue(d.getFieldSchema(), new TypeReference<List<FieldSchema>>() {}));
            } catch (Exception e) {
                log.warn("failed to parse field_schema for dataset {}: {}", d.getId(), e.getMessage());
            }
        }
        return dto;
    }
}
