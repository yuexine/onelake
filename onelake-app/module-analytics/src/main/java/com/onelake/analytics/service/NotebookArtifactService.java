package com.onelake.analytics.service;

import com.onelake.analytics.domain.entity.Dataset;
import com.onelake.analytics.domain.enums.SourceType;
import com.onelake.analytics.repository.DatasetRepository;
import com.onelake.common.audit.AuditLogger;
import com.onelake.common.context.TenantContext;
import com.onelake.common.outbox.DomainEvents;
import com.onelake.common.outbox.OutboxPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Notebook 产出资产注册服务（§7.11 P4d）。
 *
 * 链路：
 *   Notebook publish(df, name)
 *   → Spark 写 Iceberg 表
 *   → 控制面 NotebookArtifactService.register(fqn, classification, notebookId)
 *   → 发 Outbox ANALYTICS_NOTEBOOK_ARTIFACT_PUBLISHED
 *   → module-catalog 登记资产（catalog.asset）
 *   → 本模块建 dataset 记录（source_type=NOTEBOOK, asset_fqn=fqn）
 *
 * P4d 启用；P1-P4c 占位（依赖 P4c 调度链路）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotebookArtifactService {

    private final DatasetRepository datasetRepo;
    private final OutboxPublisher outbox;
    private final AuditLogger audit;

    @Transactional
    public Dataset register(String fqn, String classification, String description, UUID notebookId) {
        UUID tenant = TenantContext.getTenantId();
        if (tenant == null) {
            throw new IllegalStateException("Notebook 产出注册必须在 tenant context 下");
        }

        // 1) 建立 analytics.dataset 记录（source_type=NOTEBOOK）
        String dsName = fqn.substring(fqn.lastIndexOf('.') + 1);
        if (datasetRepo.existsByTenantIdAndName(tenant, dsName)) {
            // 幂等：已存在则直接返回
            log.info("dataset {} already exists for tenant {}, skip create", dsName, tenant);
            return datasetRepo.findByTenantIdAndName(tenant, dsName).orElseThrow();
        }

        Dataset ds = new Dataset();
        ds.setTenantId(tenant);
        ds.setName(dsName);
        ds.setSourceType(SourceType.NOTEBOOK);
        ds.setAssetFqn(fqn);
        ds.setSelectSql("SELECT * FROM " + fqn);
        ds.setClassification(classification == null ? "L1" : classification);
        ds.setCacheTtlSec(60);  // Notebook 产出默认短缓存
        ds.setCreatedBy(TenantContext.getUserId());
        datasetRepo.save(ds);

        // 2) 发 Outbox 事件：catalog 登记新资产 + audit
        outbox.publish(DomainEvents.ANALYTICS_NOTEBOOK_ARTIFACT_PUBLISHED, ds.getId().toString(),
            Map.of(
                "fqn", fqn,
                "classification", ds.getClassification(),
                "datasetId", ds.getId().toString(),
                "notebookId", notebookId == null ? "" : notebookId.toString(),
                "tenantId", tenant.toString()
            ));
        audit.auditCreate("analytics.notebook.artifact", ds.getId(), fqn);
        log.info("registered notebook artifact: {} (tenant={})", fqn, tenant);
        return ds;
    }
}
