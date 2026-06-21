package com.onelake.dataservice.service;

import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.dataservice.domain.entity.ApiDefinition;
import com.onelake.dataservice.repository.ApiDefinitionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SqlApiRuntimeServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID API_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private ApiDefinitionRepository apiRepo;
    private SqlApiRuntimeService service;

    @BeforeEach
    void setUp() {
        apiRepo = mock(ApiDefinitionRepository.class);
        service = new SqlApiRuntimeService(apiRepo);
        TenantContext.setTenantId(TENANT_ID);
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
