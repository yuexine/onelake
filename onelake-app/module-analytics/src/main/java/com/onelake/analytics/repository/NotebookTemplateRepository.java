package com.onelake.analytics.repository;

import com.onelake.analytics.domain.entity.NotebookTemplate;
import com.onelake.analytics.domain.enums.TemplateCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface NotebookTemplateRepository extends JpaRepository<NotebookTemplate, UUID> {

    /**
     * 列出可见模板：租户自定义 + 平台预置（tenant_id IS NULL）。
     */
    List<NotebookTemplate> findByTenantIdOrTenantIdIsNullOrderBySortOrder(UUID tenantId);

    List<NotebookTemplate> findByTenantIdOrTenantIdIsNullAndCategoryOrderBySortOrder(UUID tenantId, TemplateCategory category);
}
