package com.onelake.dataservice.service;

import com.onelake.catalog.service.sql.SqlAssetSecurityService;
import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.dataservice.domain.entity.ApiDefinition;
import com.onelake.dataservice.repository.ApiCallLogRepository;
import com.onelake.dataservice.repository.ApiDefinitionRepository;
import com.onelake.dataservice.repository.AppKeyRepository;
import com.onelake.dataservice.repository.QuotaUsageRepository;
import com.onelake.dataservice.repository.SubscriptionRepository;
import com.onelake.security.service.SecurityService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SqlApiRuntimeServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID API_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private ApiDefinitionRepository apiRepo;
    private AppKeyRepository appKeyRepo;
    private SubscriptionRepository subscriptionRepo;
    private ApiCallLogRepository callLogRepo;
    private QuotaUsageRepository quotaUsageRepo;
    private SqlAssetSecurityService assetSecurityService;
    private SecurityService securityService;
    private SqlApiRuntimeService service;

    @BeforeEach
    void setUp() {
        apiRepo = mock(ApiDefinitionRepository.class);
        appKeyRepo = mock(AppKeyRepository.class);
        subscriptionRepo = mock(SubscriptionRepository.class);
        callLogRepo = mock(ApiCallLogRepository.class);
        quotaUsageRepo = mock(QuotaUsageRepository.class);
        assetSecurityService = mock(SqlAssetSecurityService.class);
        securityService = mock(SecurityService.class);
        service = new SqlApiRuntimeService(
            apiRepo,
            appKeyRepo,
            subscriptionRepo,
            callLogRepo,
            quotaUsageRepo,
            assetSecurityService,
            securityService
        );
        TenantContext.setTenantId(TENANT_ID);
        TenantContext.setUserId(USER_ID);
        when(assetSecurityService.validateAndPlan(anyString(), anyInt(), anyString()))
            .thenReturn(SqlAssetSecurityService.SqlAssetSecurityContext.empty());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void debugRejectsMissingNamedParamBeforeConnectingToTrino() {
        ApiDefinition api = api("select * from ods.orders where order_id = :order_id");
        when(apiRepo.findByTenantIdAndId(TENANT_ID, API_ID)).thenReturn(Optional.of(api));

        assertThatThrownBy(() -> service.debug(API_ID, Map.of()))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("缺少 API 调试参数");
    }

    @Test
    void debugRejectsWriteSqlBeforeConnectingToTrino() {
        ApiDefinition api = api("drop table ods.orders");
        when(apiRepo.findByTenantIdAndId(TENANT_ID, API_ID)).thenReturn(Optional.of(api));

        assertThatThrownBy(() -> service.debug(API_ID, Map.of()))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("只读查询");
    }

    @Test
    void debugRejectsUnregisteredCatalogAssetBeforeConnectingToTrino() {
        ApiDefinition api = api("select * from ods.orders where order_id = :order_id");
        when(apiRepo.findByTenantIdAndId(TENANT_ID, API_ID)).thenReturn(Optional.of(api));
        doThrow(new BizException(40351, "SQL API 引用资产未登记到 Catalog: ods.orders"))
            .when(assetSecurityService).validateAndPlan(anyString(), anyInt(), anyString());

        assertThatThrownBy(() -> service.debug(API_ID, Map.of("order_id", 1001)))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("未登记到 Catalog");
    }

    @Test
    void invokeRejectsMissingAppKeyBeforeConnectingToTrino() {
        ApiDefinition api = api("select * from ods.orders");
        api.setStatus("PUBLISHED");
        when(apiRepo.findByApiPath("/sql/orders")).thenReturn(Optional.of(api));

        assertThatThrownBy(() -> service.invoke("/sql/orders", Map.of(), null, "127.0.0.1"))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("缺少 X-App-Key");
    }

    private ApiDefinition api(String sql) {
        ApiDefinition api = new ApiDefinition();
        api.setId(API_ID);
        api.setTenantId(TENANT_ID);
        api.setApiPath("/sql/orders");
        api.setViewName("v_orders");
        api.setSelectSql(sql);
        return api;
    }
}
