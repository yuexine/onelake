package com.onelake.integration.client;

import com.onelake.common.util.JsonUtil;
import com.onelake.integration.api.vo.ConnectivityResult;
import com.onelake.integration.domain.entity.DataSource;
import com.onelake.integration.domain.enums.DataSourceType;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectivityTesterTest {

    private final ConnectivityTester tester = new ConnectivityTester();

    @Test
    void testReturnsNetErrorWhenHostOrPortMissing() {
        DataSource ds = datasource(DataSourceType.S3, Map.of("host", "", "port", 0));

        ConnectivityResult result = tester.test(ds);

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo("NET");
        assertThat(result.message()).contains("host/port 缺失");
        assertThat(result.diagnostics()).containsEntry("port", 0);
    }

    @Test
    void testReturnsOkForReachableNonRdbmsSocket() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Void> accepted = CompletableFuture.runAsync(() -> {
                try (var ignored = server.accept()) {
                    // Single TCP accept is enough for non-RDBMS connectivity probing.
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            DataSource ds = datasource(DataSourceType.S3, Map.of("host", "127.0.0.1", "port", server.getLocalPort()));

            ConnectivityResult result = tester.test(ds);

            assertThat(result.ok()).isTrue();
            assertThat(result.errorCode()).isNull();
            assertThat(result.message()).isEqualTo("连通");
            assertThat(result.diagnostics()).containsEntry("type", DataSourceType.S3);
            accepted.get(1, TimeUnit.SECONDS);
        }
    }

    private DataSource datasource(DataSourceType type, Map<String, Object> config) {
        DataSource ds = new DataSource();
        ds.setId(UUID.randomUUID());
        ds.setName("probe-target");
        ds.setType(type);
        ds.setConfig(JsonUtil.toJson(config));
        return ds;
    }
}
