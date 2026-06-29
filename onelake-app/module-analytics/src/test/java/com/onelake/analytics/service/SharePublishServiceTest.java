package com.onelake.analytics.service;

import com.onelake.analytics.api.vo.DashboardPublishRequest;
import com.onelake.analytics.domain.entity.Dashboard;
import com.onelake.analytics.domain.entity.DashboardPublication;
import com.onelake.analytics.domain.entity.Dataset;
import com.onelake.analytics.domain.enums.DashboardStatus;
import com.onelake.analytics.domain.enums.SourceType;
import com.onelake.analytics.repository.DatasetRepository;
import com.onelake.analytics.repository.DashboardPublicationRepository;
import com.onelake.common.audit.AuditLogger;
import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.outbox.OutboxPublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SharePublishService 单元测试 —— 覆盖 v1.1 §5.3 公开分享硬约束：
 * 1. isPublic=true 且数据集带 row_filter → 拒绝发布（数据泄露防护）
 * 2. isPublic=true 且数据集无 row_filter → 签发 shareToken + isCurrent + Outbox
 * 3. isPublic=false 不校验 row_filter
 */
class SharePublishServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private DashboardService dashboardService;
    private DatasetRepository datasetRepo;
    private DashboardPublicationRepository pubRepo;
    private OutboxPublisher outbox;
    private AuditLogger audit;

    private SharePublishService service;

    @BeforeEach
    void setUp() {
        dashboardService = mock(DashboardService.class);
        datasetRepo = mock(DatasetRepository.class);
        pubRepo = mock(DashboardPublicationRepository.class);
        outbox = mock(OutboxPublisher.class);
        audit = mock(AuditLogger.class);

        service = new SharePublishService(dashboardService, datasetRepo, pubRepo, outbox, audit);
        TenantContext.setTenantId(TENANT_ID);
        TenantContext.setUsername("analyst-1");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void publicShare_withRowFilterDataset_rejected() {
        UUID dashboardId = UUID.randomUUID();
        Dashboard d = dashboardWithWidgets(dashboardId, /* hasRowFilter */ true);
        when(dashboardService.get(dashboardId)).thenReturn(d);

        DashboardPublishRequest req = new DashboardPublishRequest();
        req.setIsPublic(true);

        assertThatThrownBy(() -> service.publish(dashboardId, req))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("公开分享不允许数据集带 row_filter");
    }

    @Test
    void publicShare_withoutRowFilterDataset_issuesShareTokenAndOutbox() {
        UUID dashboardId = UUID.randomUUID();
        Dashboard d = dashboardWithWidgets(dashboardId, /* hasRowFilter */ false);
        when(dashboardService.get(dashboardId)).thenReturn(d);
        when(pubRepo.clearCurrentForDashboard(dashboardId)).thenReturn(1);
        when(pubRepo.findByDashboardIdAndIsCurrentTrue(dashboardId)).thenReturn(Optional.empty());
        when(pubRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(dashboardService.save(eq(dashboardId), any())).thenReturn(d);

        DashboardPublishRequest req = new DashboardPublishRequest();
        req.setIsPublic(true);

        DashboardPublication pub = service.publish(dashboardId, req);

        assertThat(pub.getIsPublic()).isTrue();
        assertThat(pub.getShareToken()).isNotBlank();
        assertThat(pub.getShareToken().length()).isEqualTo(64);  // 32 bytes hex
        assertThat(pub.getIsCurrent()).isTrue();
        verify(outbox).publish(eq("analytics.dashboard.published"), eq(dashboardId.toString()), any());
    }

    @Test
    void internalPublish_noRowFilterCheck() {
        UUID dashboardId = UUID.randomUUID();
        // 内部发布即使有 row_filter 也不应该被拒
        Dashboard d = dashboardWithWidgets(dashboardId, /* hasRowFilter */ true);
        when(dashboardService.get(dashboardId)).thenReturn(d);
        when(pubRepo.clearCurrentForDashboard(dashboardId)).thenReturn(1);
        when(pubRepo.findByDashboardIdAndIsCurrentTrue(dashboardId)).thenReturn(Optional.empty());
        when(pubRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(dashboardService.save(eq(dashboardId), any())).thenReturn(d);

        DashboardPublishRequest req = new DashboardPublishRequest();
        req.setIsPublic(false);

        DashboardPublication pub = service.publish(dashboardId, req);

        assertThat(pub.getIsPublic()).isFalse();
        assertThat(pub.getShareToken()).isNull();
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(outbox).publish(eq("analytics.dashboard.published"), any(), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue().get("isPublic")).isEqualTo(false);
    }

    @Test
    void getByShareToken_expired_throws() {
        DashboardPublication pub = new DashboardPublication();
        pub.setShareToken("tok123");
        pub.setIsPublic(true);
        pub.setExpireAt(Instant.now().minusSeconds(60));
        when(pubRepo.findByShareToken("tok123")).thenReturn(Optional.of(pub));

        assertThatThrownBy(() -> service.getByShareToken("tok123"))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("过期");
    }

    @Test
    void getByShareToken_notPublic_throws() {
        DashboardPublication pub = new DashboardPublication();
        pub.setShareToken("tok123");
        pub.setIsPublic(false);
        when(pubRepo.findByShareToken("tok123")).thenReturn(Optional.of(pub));

        assertThatThrownBy(() -> service.getByShareToken("tok123"))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("未开启公开");
    }

    // ============ helpers ============

    /**
     * 构造一个 dashboard，spec 中含一个绑定数据集的 widget。
     * 数据集 row_filter 由 hasRowFilter 参数控制。
     */
    private Dashboard dashboardWithWidgets(UUID dashboardId, boolean hasRowFilter) {
        Dashboard d = new Dashboard();
        d.setId(dashboardId);
        d.setTenantId(TENANT_ID);
        d.setName("测试大屏");
        d.setStatus(DashboardStatus.DRAFT);
        d.setVersion(1);
        d.setCanvas("{\"width\":1920,\"height\":1080,\"theme\":\"dark\"}");

        UUID datasetId = UUID.randomUUID();
        // spec 含一个绑定该 datasetId 的 widget（用裸 JSON 字符串模拟前端的 ScreenSpec）
        d.setSpec("{\"widgets\":[{\"id\":\"w1\",\"type\":\"line\",\"data\":{\"datasetId\":\""
            + datasetId + "\",\"dimensions\":[],\"measures\":[]}}]}");

        Dataset ds = new Dataset();
        ds.setId(datasetId);
        ds.setName("test_ds");
        ds.setSourceType(SourceType.ASSET);
        ds.setAssetFqn("iceberg.dwd.dwd_user");
        if (hasRowFilter) {
            ds.setRowFilter("region = '华东'");
        }
        when(datasetRepo.findById(datasetId)).thenReturn(Optional.of(ds));
        return d;
    }
}
