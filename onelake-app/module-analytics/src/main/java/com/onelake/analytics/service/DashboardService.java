package com.onelake.analytics.service;

import com.onelake.analytics.api.vo.DashboardSaveRequest;
import com.onelake.analytics.domain.entity.Dashboard;
import com.onelake.analytics.domain.enums.DashboardStatus;
import com.onelake.analytics.repository.DashboardRepository;
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
 * 大屏服务（CRUD + 保存草稿）。
 * 发布动作见 SharePublishService（§7.7 v1.1）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final DashboardRepository repo;
    private final AuditLogger audit;

    @Transactional
    public Dashboard create(String name, String description) {
        UUID tenant = TenantContext.getTenantId();
        Dashboard d = new Dashboard();
        d.setTenantId(tenant);
        d.setName(name);
        d.setDescription(description);
        d.setCanvas("{\"width\":1920,\"height\":1080,\"theme\":\"dark\",\"background\":\"#0a1a2f\"}");
        d.setSpec("[]");
        d.setStatus(DashboardStatus.DRAFT);
        d.setVersion(0);
        d.setCreatedBy(TenantContext.getUserId());
        repo.save(d);
        audit.auditCreate("analytics.dashboard", d.getId(), name);
        return d;
    }

    @Transactional
    public Dashboard save(UUID id, DashboardSaveRequest req) {
        UUID tenant = TenantContext.getTenantId();
        Dashboard d = repo.findByIdAndTenantId(id, tenant)
            .orElseThrow(() -> new BizException(40400, "大屏不存在"));

        // 乐观锁校验
        if (req.getExpectedVersion() != null && !req.getExpectedVersion().equals(d.getVersion())) {
            throw new BizException(40901, "大屏已被他人修改，请刷新后重试");
        }

        if (req.getName() != null) d.setName(req.getName());
        if (req.getDescription() != null) d.setDescription(req.getDescription());
        if (req.getCanvas() != null) d.setCanvas(JsonUtil.toJson(req.getCanvas()));
        if (req.getSpec() != null) d.setSpec(JsonUtil.toJson(req.getSpec()));
        if (req.getThumbnail() != null) d.setThumbnail(req.getThumbnail());
        d.setVersion(d.getVersion() + 1);
        d.setUpdatedAt(Instant.now());
        repo.save(d);
        audit.auditUpdate("analytics.dashboard", id, "save draft v" + d.getVersion());
        return d;
    }

    @Transactional(readOnly = true)
    public Dashboard get(UUID id) {
        UUID tenant = TenantContext.getTenantId();
        return repo.findByIdAndTenantId(id, tenant)
            .orElseThrow(() -> new BizException(40400, "大屏不存在"));
    }

    @Transactional(readOnly = true)
    public List<Dashboard> list() {
        return repo.findByTenantId(TenantContext.getTenantId());
    }

    @Transactional
    public void delete(UUID id) {
        UUID tenant = TenantContext.getTenantId();
        Dashboard d = repo.findByIdAndTenantId(id, tenant)
            .orElseThrow(() -> new BizException(40400, "大屏不存在"));
        repo.delete(d);
        audit.auditDelete("analytics.dashboard", id);
    }
}
