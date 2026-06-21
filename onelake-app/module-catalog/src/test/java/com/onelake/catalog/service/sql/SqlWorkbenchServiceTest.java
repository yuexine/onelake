package com.onelake.catalog.service.sql;

import com.onelake.catalog.domain.entity.sql.SavedQuery;
import com.onelake.catalog.domain.entity.sql.SqlQueryHistory;
import com.onelake.catalog.dto.sql.SqlExecuteRequest;
import com.onelake.catalog.dto.sql.SqlSaveQueryRequest;
import com.onelake.catalog.repository.sql.SavedQueryRepository;
import com.onelake.catalog.repository.sql.SqlQueryHistoryRepository;
import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SqlWorkbenchServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private SqlQueryHistoryRepository historyRepo;
    private SavedQueryRepository savedQueryRepo;
    private SqlWorkbenchService service;

    @BeforeEach
    void setUp() {
        historyRepo = mock(SqlQueryHistoryRepository.class);
        savedQueryRepo = mock(SavedQueryRepository.class);
        service = new SqlWorkbenchService(historyRepo, savedQueryRepo);
        ReflectionTestUtils.setField(service, "scanThresholdBytes", 1024L);
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
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void estimateAcceptsReadOnlySql() {
        var estimate = service.estimate(new SqlExecuteRequest("select * from ods.orders", "auto", "rg-default"));

        assertThat(estimate.engine()).isEqualTo("TRINO");
        assertThat(estimate.message()).contains("只读校验");
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
    void executeRejectsWriteSqlAndRecordsFailure() {
        assertThatThrownBy(() -> service.execute(new SqlExecuteRequest("drop table ods.orders", "trino", "rg-default")))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("只读查询");

        org.mockito.ArgumentCaptor<SqlQueryHistory> captor = org.mockito.ArgumentCaptor.forClass(SqlQueryHistory.class);
        verify(historyRepo, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues().get(captor.getAllValues().size() - 1).getStatus()).isEqualTo("FAILED");
    }

    @Test
    void executeRejectsUnsupportedEngine() {
        assertThatThrownBy(() -> service.execute(new SqlExecuteRequest("select 1", "spark", "rg-default")))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("当前仅支持 Trino");
    }
}
