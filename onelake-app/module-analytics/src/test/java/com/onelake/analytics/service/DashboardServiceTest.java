package com.onelake.analytics.service;

import com.onelake.analytics.api.vo.DashboardSaveRequest;
import com.onelake.analytics.domain.entity.Dashboard;
import com.onelake.analytics.domain.enums.DashboardStatus;
import com.onelake.analytics.repository.DashboardRepository;
import com.onelake.common.audit.AuditLogger;
import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
 * DashboardService 单元测试 —— 覆盖乐观锁冲突：
 * 客户端提交的 expectedVersion 与当前 version 不符 → 拒绝保存（防并发覆盖）。
 */
class DashboardServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private DashboardRepository repo;
    private AuditLogger audit;
    private DashboardService service;

    @BeforeEach
    void setUp() {
        repo = mock(DashboardRepository.class);
        audit = mock(AuditLogger.class);
        service = new DashboardService(repo, audit);
        TenantContext.setTenantId(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void save_expectedVersionMatches_persistsAndIncrementsVersion() {
        UUID dashboardId = UUID.randomUUID();
        Dashboard d = buildDashboard(dashboardId, 3);
        when(repo.findByIdAndTenantId(dashboardId, TENANT_ID)).thenReturn(Optional.of(d));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DashboardSaveRequest req = new DashboardSaveRequest();
        req.setExpectedVersion(3);
        req.setName("更新后的名称");

        Dashboard saved = service.save(dashboardId, req);

        assertThat(saved.getName()).isEqualTo("更新后的名称");
        assertThat(saved.getVersion()).isEqualTo(4);
        verify(repo).save(any(Dashboard.class));
    }

    @Test
    void save_versionMismatch_throwsOptimisticLockException() {
        UUID dashboardId = UUID.randomUUID();
        Dashboard d = buildDashboard(dashboardId, 5);
        when(repo.findByIdAndTenantId(dashboardId, TENANT_ID)).thenReturn(Optional.of(d));

        DashboardSaveRequest req = new DashboardSaveRequest();
        req.setExpectedVersion(3);  // stale，与 server v5 不符

        assertThatThrownBy(() -> service.save(dashboardId, req))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("已被他人修改");
    }

    @Test
    void save_dashboardNotFound_throws() {
        UUID dashboardId = UUID.randomUUID();
        when(repo.findByIdAndTenantId(dashboardId, TENANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.save(dashboardId, new DashboardSaveRequest()))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("大屏不存在");
    }

    @Test
    void create_initialDashboardWithDraftStatusAndZeroVersion() {
        when(repo.save(any())).thenAnswer(inv -> {
            Dashboard saved = inv.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });

        Dashboard created = service.create("新大屏", "描述");

        assertThat(created.getStatus()).isEqualTo(DashboardStatus.DRAFT);
        assertThat(created.getVersion()).isEqualTo(0);
        assertThat(created.getSpec()).isEqualTo("[]");
        assertThat(created.getCanvas()).contains("1920");
        verify(audit).auditCreate(eq("analytics.dashboard"), any(), eq("新大屏"));
    }

    private Dashboard buildDashboard(UUID id, int version) {
        Dashboard d = new Dashboard();
        d.setId(id);
        d.setTenantId(TENANT_ID);
        d.setName("test");
        d.setStatus(DashboardStatus.DRAFT);
        d.setVersion(version);
        d.setCanvas("{\"width\":1920,\"height\":1080,\"theme\":\"dark\"}");
        d.setSpec("[]");
        return d;
    }
}
