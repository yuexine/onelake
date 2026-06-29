package com.onelake.analytics.service;

import com.onelake.analytics.domain.entity.NotebookTemplate;
import com.onelake.analytics.domain.enums.TemplateCategory;
import com.onelake.analytics.repository.NotebookTemplateRepository;
import com.onelake.common.audit.AuditLogger;
import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 算法模板服务（§6 analytics.notebook_template）。
 *
 * 平台预置（tenant_id=NULL）+ 租户自定义（tenant_id=当前租户）。
 * 平台预置在 P4d 启动时由 DatabaseInitializer 注入（KMeans / Prophet / 相关性 / RFM 等）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotebookTemplateService {

    private final NotebookTemplateRepository repo;
    private final AuditLogger audit;

    @Transactional(readOnly = true)
    public List<NotebookTemplate> list(TemplateCategory category) {
        UUID tenant = TenantContext.getTenantId();
        return category == null
            ? repo.findByTenantIdOrTenantIdIsNullOrderBySortOrder(tenant)
            : repo.findByTenantIdOrTenantIdIsNullAndCategoryOrderBySortOrder(tenant, category);
    }

    @Transactional(readOnly = true)
    public NotebookTemplate get(UUID id) {
        return repo.findById(id).orElseThrow(() -> new BizException(40400, "模板不存在"));
    }

    @Transactional
    public NotebookTemplate create(String name, TemplateCategory category, String description,
                                   String storagePath, String paramsSchemaJson, String kernel) {
        UUID tenant = TenantContext.getTenantId();
        NotebookTemplate t = new NotebookTemplate();
        t.setTenantId(tenant);  // 平台预置 NULL 不走此入口
        t.setName(name);
        t.setCategory(category);
        t.setDescription(description);
        t.setStoragePath(storagePath);
        if (paramsSchemaJson != null) {
            t.setParamsSchema(paramsSchemaJson);
        }
        t.setKernel(kernel == null ? "python3" : kernel);
        t.setSortOrder((int) repo.count() + 1);
        t.setCreatedBy(TenantContext.getUserId());
        repo.save(t);
        audit.auditCreate("analytics.notebook_template", t.getId(), name);
        return t;
    }

    @Transactional
    public void delete(UUID id) {
        NotebookTemplate t = get(id);
        if (t.getTenantId() == null) {
            throw new BizException(40003, "平台预置模板不可删除");
        }
        repo.delete(t);
        audit.auditDelete("analytics.notebook_template", id);
    }
}
