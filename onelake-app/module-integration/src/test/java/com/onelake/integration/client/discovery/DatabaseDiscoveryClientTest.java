package com.onelake.integration.client.discovery;

import com.onelake.common.exception.BizException;
import com.onelake.integration.domain.enums.DataSourceType;
import com.onelake.integration.dto.DiscoveredColumnDTO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatabaseDiscoveryClientTest {

    @Test
    void discoverDelegatesToMatchedStrategy() {
        Map<String, Object> config = Map.of("host", "db.internal");
        DataSourceDiscoveryStrategy mysql = strategy(DataSourceType.MYSQL);
        when(mysql.discover(config)).thenReturn(List.of("orders", "inventory"));
        DatabaseDiscoveryClient client = new DatabaseDiscoveryClient(List.of(mysql));

        List<String> result = client.discover(DataSourceType.MYSQL, config);

        assertThat(result).containsExactly("orders", "inventory");
        verify(mysql).discover(config);
    }

    @Test
    void tableDiscoveryDelegatesSchemaToMatchedStrategy() {
        Map<String, Object> config = Map.of("host", "db.internal");
        DataSourceDiscoveryStrategy postgres = strategy(DataSourceType.POSTGRES);
        when(postgres.listTables(config, "public")).thenReturn(List.of("public.orders"));
        DatabaseDiscoveryClient client = new DatabaseDiscoveryClient(List.of(postgres));

        List<String> result = client.listTables(DataSourceType.POSTGRES, config, "public");

        assertThat(result).containsExactly("public.orders");
        verify(postgres).listTables(config, "public");
    }

    @Test
    void columnDiscoveryDelegatesObjectNameToMatchedStrategy() {
        Map<String, Object> config = Map.of("host", "db.internal");
        DataSourceDiscoveryStrategy postgres = strategy(DataSourceType.POSTGRES);
        when(postgres.describeTable(config, "public.orders"))
            .thenReturn(List.of(new DiscoveredColumnDTO("id", "bigint", false, true, 1)));
        DatabaseDiscoveryClient client = new DatabaseDiscoveryClient(List.of(postgres));

        List<DiscoveredColumnDTO> result = client.describeTable(DataSourceType.POSTGRES, config, "public.orders");

        assertThat(result)
            .extracting(DiscoveredColumnDTO::name)
            .containsExactly("id");
        verify(postgres).describeTable(config, "public.orders");
    }

    @Test
    void unsupportedTypeKeepsOperationSpecificMessages() {
        DatabaseDiscoveryClient client = new DatabaseDiscoveryClient(List.of(strategy(DataSourceType.MYSQL)));

        assertThatThrownBy(() -> client.discover(DataSourceType.S3, Map.of()))
            .isInstanceOf(BizException.class)
            .hasMessage("当前类型暂不支持库列表探查，请手动输入");
        assertThatThrownBy(() -> client.listSchemas(DataSourceType.S3, Map.of()))
            .isInstanceOf(BizException.class)
            .hasMessage("当前类型暂不支持 schema 探查");
        assertThatThrownBy(() -> client.listTables(DataSourceType.S3, Map.of(), null))
            .isInstanceOf(BizException.class)
            .hasMessage("当前类型暂不支持表探查");
        assertThatThrownBy(() -> client.describeTable(DataSourceType.S3, Map.of(), "orders"))
            .isInstanceOf(BizException.class)
            .hasMessage("当前类型暂不支持字段探查");
    }

    private DataSourceDiscoveryStrategy strategy(DataSourceType type) {
        DataSourceDiscoveryStrategy strategy = mock(DataSourceDiscoveryStrategy.class);
        when(strategy.type()).thenReturn(type);
        return strategy;
    }
}
