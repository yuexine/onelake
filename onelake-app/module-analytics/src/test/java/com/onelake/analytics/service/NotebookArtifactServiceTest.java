package com.onelake.analytics.service;

import com.onelake.analytics.domain.entity.Dataset;
import com.onelake.analytics.domain.enums.SourceType;
import com.onelake.analytics.repository.DatasetRepository;
import com.onelake.common.audit.AuditLogger;
import com.onelake.common.context.TenantContext;
import com.onelake.common.outbox.DomainEvents;
import com.onelake.common.outbox.OutboxPublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * NotebookArtifactService 单元测试 —— 覆盖幂等性 + Outbox 事件触发。
 */
class NotebookArtifactServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID NOTEBOOK_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private DatasetRepository datasetRepo;
    private OutboxPublisher outbox;
    private AuditLogger audit;

    private NotebookArtifactService service;

    @BeforeEach
    void setUp() {
        datasetRepo = mock(DatasetRepository.class);
        outbox = mock(OutboxPublisher.class);
        audit = mock(AuditLogger.class);
        service = new NotebookArtifactService(datasetRepo, outbox, audit);
        TenantContext.setTenantId(TENANT_ID);
        TenantContext.setUsername("analyst-1");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void register_newArtifact_createsDatasetAndPublishesOutbox() {
        when(datasetRepo.existsByTenantIdAndName(TENANT_ID, "ads_user_rfm_seg")).thenReturn(false);
        when(datasetRepo.save(any())).thenAnswer(inv -> {
            Dataset saved = inv.getArgument(0);
            saved.setId(UUID.randomUUID());  // 模拟 JPA @GeneratedValue
            return saved;
        });

        Dataset ds = service.register(
            "iceberg.dwd.ads_user_rfm_seg",
            "L2",
            "RFM 客户分群",
            NOTEBOOK_ID
        );

        assertThat(ds.getName()).isEqualTo("ads_user_rfm_seg");
        assertThat(ds.getSourceType()).isEqualTo(SourceType.NOTEBOOK);
        assertThat(ds.getAssetFqn()).isEqualTo("iceberg.dwd.ads_user_rfm_seg");
        assertThat(ds.getClassification()).isEqualTo("L2");

        // 验证 Outbox 事件触发（catalog 模块会消费这个事件登记新资产）
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(outbox).publish(
            eq(DomainEvents.ANALYTICS_NOTEBOOK_ARTIFACT_PUBLISHED),
            anyString(),
            payloadCaptor.capture());
        Map<String, Object> payload = payloadCaptor.getValue();
        assertThat(payload.get("fqn")).isEqualTo("iceberg.dwd.ads_user_rfm_seg");
        assertThat(payload.get("notebookId")).isEqualTo(NOTEBOOK_ID.toString());
        assertThat(payload.get("tenantId")).isEqualTo(TENANT_ID.toString());
    }

    @Test
    void register_alreadyExisting_isIdempotent_skipsCreate() {
        Dataset existing = new Dataset();
        existing.setId(UUID.randomUUID());
        existing.setTenantId(TENANT_ID);
        existing.setName("ads_user_rfm_seg");
        existing.setSourceType(SourceType.NOTEBOOK);

        when(datasetRepo.existsByTenantIdAndName(TENANT_ID, "ads_user_rfm_seg")).thenReturn(true);
        when(datasetRepo.findByTenantIdAndName(TENANT_ID, "ads_user_rfm_seg"))
            .thenReturn(Optional.of(existing));

        Dataset result = service.register(
            "iceberg.dwd.ads_user_rfm_seg", "L2", "ignored", NOTEBOOK_ID);

        assertThat(result.getId()).isEqualTo(existing.getId());
        // 关键：幂等性——已存在时不创建新记录、不发 Outbox 事件
        verify(datasetRepo, never()).save(any());
        verify(outbox, never()).publish(anyString(), anyString(), any());
    }

    @Test
    void register_missingClassification_defaultsToL1() {
        when(datasetRepo.existsByTenantIdAndName(any(), any())).thenReturn(false);
        when(datasetRepo.save(any())).thenAnswer(inv -> {
            Dataset saved = inv.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });

        Dataset ds = service.register("iceberg.dwd.test", null, null, null);

        assertThat(ds.getClassification()).isEqualTo("L1");
    }
}
