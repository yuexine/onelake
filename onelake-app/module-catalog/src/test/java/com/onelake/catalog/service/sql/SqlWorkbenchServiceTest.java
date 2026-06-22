package com.onelake.catalog.service.sql;

import com.onelake.catalog.domain.entity.sql.SavedQuery;
import com.onelake.catalog.domain.entity.sql.SqlQueryHistory;
import com.onelake.catalog.dto.sql.SqlExecuteRequest;
import com.onelake.catalog.dto.sql.SqlSaveQueryRequest;
import com.onelake.catalog.repository.sql.SavedQueryRepository;
import com.onelake.catalog.repository.sql.SqlQueryHistoryRepository;
import com.onelake.common.audit.AuditLogger;
import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.security.service.AclService;
import com.onelake.security.service.SecurityService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SqlWorkbenchServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private SqlQueryHistoryRepository historyRepo;
    private SavedQueryRepository savedQueryRepo;
    private SqlAssetSecurityService assetSecurityService;
    private SecurityService securityService;
    private AuditLogger auditLogger;
    private AclService aclService;
    private DataSource trinoDataSource;
    private SqlWorkbenchService service;

    @BeforeEach
    void setUp() {
        historyRepo = mock(SqlQueryHistoryRepository.class);
        savedQueryRepo = mock(SavedQueryRepository.class);
        assetSecurityService = mock(SqlAssetSecurityService.class);
        securityService = mock(SecurityService.class);
        auditLogger = mock(AuditLogger.class);
        aclService = mock(AclService.class);
        trinoDataSource = mock(DataSource.class);
        service = new SqlWorkbenchService(historyRepo, savedQueryRepo, assetSecurityService, securityService, auditLogger, aclService, trinoDataSource);
        ReflectionTestUtils.setField(service, "scanThresholdBytes", 1024L);
        // ACL mock 默认允许：让 owner 外的 requireEdit / filterViewable 通过
        org.mockito.Mockito.doNothing().when(aclService).requireEdit(any(), any(), any());
        org.mockito.Mockito.doNothing().when(aclService).autoGrantOnShared(any(), any());
        org.mockito.Mockito.doNothing().when(aclService).autoRevokeOnPrivate(any(), any());
        org.mockito.Mockito.doNothing().when(aclService).cleanupOnDelete(any(), any());
        org.mockito.Mockito.when(aclService.filterViewable(any(), any(), org.mockito.ArgumentMatchers.any()))
            .thenAnswer(invocation -> invocation.getArgument(0));
        TenantContext.setTenantId(TENANT_ID);
        TenantContext.setUserId(USER_ID);
        TenantContext.setUsername("dev");
        when(historyRepo.save(any(SqlQueryHistory.class))).thenAnswer(invocation -> {
            SqlQueryHistory history = invocation.getArgument(0);
            if (history.getId() == null) {
                ReflectionTestUtils.setField(history, "id", UUID.randomUUID());
            }
            return history;
        });
        when(assetSecurityService.validateAndPlan(anyString(), anyInt(), anyString()))
            .thenReturn(SqlAssetSecurityService.SqlAssetSecurityContext.empty());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void estimateAcceptsReadOnlySql() {
        var estimate = service.estimate(new SqlExecuteRequest("select * from ods.orders", "auto", "rg-default", null));

        assertThat(estimate.engine()).isEqualTo("TRINO");
        assertThat(estimate.message()).contains("只读校验");
    }

    @Test
    void parseEstimateBytesFromExplainJson() {
        Long bytes = SqlWorkbenchService.parseEstimatedScanBytes("""
            {"inputTableColumnInfos":[{"table":"ods.orders","estimatedSizeInBytes":1048576}]}
            """);

        assertThat(bytes).isEqualTo(1_048_576L);
    }

    @Test
    void saveQueryPersistsOwnerAndSql() {
        when(savedQueryRepo.findByTenantIdAndName(TENANT_ID, "日订单")).thenReturn(Optional.empty());
        when(savedQueryRepo.save(any(SavedQuery.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.saveQuery(new SqlSaveQueryRequest("日订单", "select * from ods.orders", true));

        org.mockito.ArgumentCaptor<SavedQuery> captor = org.mockito.ArgumentCaptor.forClass(SavedQuery.class);
        verify(savedQueryRepo).save(captor.capture());
        assertThat(captor.getValue().getTenantId()).isEqualTo(TENANT_ID);
        assertThat(captor.getValue().getOwnerId()).isEqualTo(USER_ID);
        assertThat(captor.getValue().getOwnerName()).isEqualTo("dev");
        assertThat(captor.getValue().getSqlText()).isEqualTo("select * from ods.orders");
        assertThat(captor.getValue().isShared()).isTrue();
    }

    @Test
    void updateSavedQueryPreservesOwnershipAndChangesSql() {
        UUID id = UUID.randomUUID();
        SavedQuery query = new SavedQuery();
        query.setTenantId(TENANT_ID);
        query.setOwnerId(USER_ID);
        query.setOwnerName("dev");
        query.setName("旧名称");
        query.setSqlText("select 1");
        when(savedQueryRepo.findByTenantIdAndId(TENANT_ID, id)).thenReturn(Optional.of(query));
        when(savedQueryRepo.save(any(SavedQuery.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var saved = service.updateSavedQuery(id, new SqlSaveQueryRequest("新名称", "select * from ods.orders", true));

        assertThat(saved.name()).isEqualTo("新名称");
        assertThat(saved.sql()).isEqualTo("select * from ods.orders");
        assertThat(saved.shared()).isTrue();
        assertThat(query.getOwnerId()).isEqualTo(USER_ID);
    }

    @Test
    void deleteSavedQueryRequiresTenantScopedRecord() {
        UUID id = UUID.randomUUID();
        SavedQuery query = new SavedQuery();
        query.setTenantId(TENANT_ID);
        when(savedQueryRepo.findByTenantIdAndId(TENANT_ID, id)).thenReturn(Optional.of(query));

        service.deleteSavedQuery(id);

        verify(savedQueryRepo).delete(query);
    }

    @Test
    void executeRejectsWriteSqlAndRecordsFailure() {
        assertThatThrownBy(() -> service.execute(new SqlExecuteRequest("drop table ods.orders", "trino", "rg-default", null)))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("只读查询");

        org.mockito.ArgumentCaptor<SqlQueryHistory> captor = org.mockito.ArgumentCaptor.forClass(SqlQueryHistory.class);
        verify(historyRepo, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues().get(captor.getAllValues().size() - 1).getStatus()).isEqualTo("FAILED");
    }

    @Test
    void executeRejectsUnsupportedEngine() {
        assertThatThrownBy(() -> service.execute(new SqlExecuteRequest("select 1", "spark", "rg-default", null)))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("当前仅支持 Trino");
    }

    @Test
    void submitRejectsUnregisteredCatalogAssetBeforeConnectingToTrino() {
        doThrow(new BizException(40341, "SQL 引用资产未登记到 Catalog: ods.orders"))
            .when(assetSecurityService).validateAndPlan(anyString(), anyInt(), anyString());

        assertThatThrownBy(() -> service.submit(new SqlExecuteRequest("select * from ods.orders", "trino", "rg-default", null)))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("未登记到 Catalog");
    }

    @Test
    void submitRejectsAssetWithoutQueryGrantBeforeConnectingToTrino() {
        doThrow(new BizException(40340, "无权查询资产: ods.orders"))
            .when(assetSecurityService).validateAndPlan(anyString(), anyInt(), anyString());

        assertThatThrownBy(() -> service.submit(new SqlExecuteRequest("select * from ods.orders", "trino", "rg-default", null)))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("无权查询资产");
    }

    @Test
    @SuppressWarnings("unchecked")
    void submitRejectedWhenConcurrencyFull() throws Exception {
        ReflectionTestUtils.setField(service, "maxRunningPerUser", 2);
        java.util.Map<UUID, SqlWorkbenchService.QueryTask> tasks =
            (java.util.Map<UUID, SqlWorkbenchService.QueryTask>) ReflectionTestUtils.getField(service, "queryTasks");
        assertThat(tasks).isNotNull();
        tasks.clear();
        for (int i = 0; i < 2; i++) {
            UUID hid = UUID.randomUUID();
            tasks.put(hid, new SqlWorkbenchService.QueryTask(
                hid, "select 1", "TRINO", "rg-default",
                TENANT_ID, USER_ID, "dev", java.time.Instant.now(), null
            ));
        }

        assertThatThrownBy(() -> service.submit(new SqlExecuteRequest("select * from ods.orders", "trino", "rg-default", null)))
            .isInstanceOf(BizException.class)
            .satisfies(err -> {
                BizException biz = (BizException) err;
                assertThat(biz.getCode()).isEqualTo(42901);
            });
    }

    @Test
    @SuppressWarnings("unchecked")
    void exportRejectedWhenConcurrencyFull() throws Exception {
        ReflectionTestUtils.setField(service, "maxRunningPerUser", 1);
        java.util.Map<UUID, SqlWorkbenchService.QueryTask> tasks =
            (java.util.Map<UUID, SqlWorkbenchService.QueryTask>) ReflectionTestUtils.getField(service, "queryTasks");
        assertThat(tasks).isNotNull();
        tasks.clear();
        UUID hid = UUID.randomUUID();
        tasks.put(hid, new SqlWorkbenchService.QueryTask(
            hid, "select 1", "TRINO", "rg-default",
            TENANT_ID, USER_ID, "dev", java.time.Instant.now(), null
        ));

        jakarta.servlet.http.HttpServletResponse resp = mock(jakarta.servlet.http.HttpServletResponse.class);
        assertThatThrownBy(() -> service.export(
            new SqlExecuteRequest("select * from ods.orders", "trino", "rg-default", null),
            "csv", null, resp
        ))
            .isInstanceOf(BizException.class)
            .satisfies(err -> {
                BizException biz = (BizException) err;
                assertThat(biz.getCode()).isEqualTo(42901);
            });
    }

    @Test
    @SuppressWarnings("unchecked")
    void cancelTerminatesRegisteredRunningTask() throws Exception {
        java.util.Map<UUID, SqlWorkbenchService.QueryTask> tasks =
            (java.util.Map<UUID, SqlWorkbenchService.QueryTask>) ReflectionTestUtils.getField(service, "queryTasks");
        assertThat(tasks).isNotNull();
        tasks.clear();
        UUID hid = UUID.randomUUID();
        SqlWorkbenchService.QueryTask task = new SqlWorkbenchService.QueryTask(
            hid, "select 1", "TRINO", "rg-default",
            TENANT_ID, USER_ID, "dev", java.time.Instant.now(), null
        );
        java.sql.Statement statement = mock(java.sql.Statement.class);
        task.statement = statement;
        tasks.put(hid, task);

        SqlQueryHistory history = new SqlQueryHistory();
        history.setId(hid);
        history.setTenantId(TENANT_ID);
        history.setStatus("RUNNING");
        history.setCreatedAt(java.time.Instant.now());
        when(historyRepo.findByTenantIdAndId(TENANT_ID, hid)).thenReturn(Optional.of(history));

        var result = service.cancel(hid);

        assertThat(result.status()).isEqualTo("CANCELLED");
        verify(statement).cancel();
        verify(historyRepo).save(org.mockito.ArgumentMatchers.any(SqlQueryHistory.class));
        // 注意：cancel 只标记 task 状态，不立即从 queryTasks 移除；移除交给后续 cleanupFinishedTasks / cleanupStaleTasks 处理。
        SqlWorkbenchService.QueryTask updated = tasks.get(hid);
        assertThat(updated).isNotNull();
        assertThat(updated.result).isNotNull();
        assertThat(updated.result.status()).isEqualTo("CANCELLED");
        assertThat(updated.cancelRequested).isTrue();
    }
}
