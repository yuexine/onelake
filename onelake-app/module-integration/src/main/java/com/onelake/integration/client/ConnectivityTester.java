package com.onelake.integration.client;

import com.onelake.integration.api.vo.ConnectivityResult;
import com.onelake.integration.domain.entity.DataSource;
import com.onelake.integration.domain.enums.DataSourceType;
import com.onelake.common.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.Map;
import java.util.Properties;

/**
 * 连接探活（对应《技术初始化文档》§6.10 / 功能清单 L1-1.1.2）。
 * - 第一步：TCP socket 探活（host:port），按 NET 错误码分类。
 * - 第二步：JDBC 下发 SELECT 1（关系库），按 AUTH/DRV 错误码分类。
 * - 5 秒超时；返回 RTT 与诊断。
 */
@Slf4j
@Component
public class ConnectivityTester {

    private static final int TIMEOUT_MS = 5000;

    public ConnectivityResult test(DataSource ds) {
        JsonNode config = JsonUtil.parse(ds.getConfig());
        String host = config.path("host").asText("");
        int port = config.path("port").asInt(0);

        long t0 = System.currentTimeMillis();
        try {
            probeTcp(host, port);

            if (isRdbms(ds.getType())) {
                probeJdbc(ds, config);
            }

            long rtt = System.currentTimeMillis() - t0;
            return new ConnectivityResult(true, null, "连通", rtt, Instant.now(),
                Map.of("host", host, "port", port, "type", ds.getType()));
        } catch (NetException e) {
            long rtt = System.currentTimeMillis() - t0;
            return new ConnectivityResult(false, "NET", e.getMessage(), rtt, Instant.now(),
                Map.of("host", host, "port", port));
        } catch (AuthException e) {
            long rtt = System.currentTimeMillis() - t0;
            return new ConnectivityResult(false, "AUTH", e.getMessage(), rtt, Instant.now(),
                Map.of("host", host, "port", port));
        } catch (DrvException e) {
            long rtt = System.currentTimeMillis() - t0;
            return new ConnectivityResult(false, "DRV", e.getMessage(), rtt, Instant.now(),
                Map.of("host", host, "port", port));
        } catch (Exception e) {
            long rtt = System.currentTimeMillis() - t0;
            log.warn("connectivity test failed for ds={} host={}:{}: {}", ds.getId(), host, port, e.getMessage());
            return new ConnectivityResult(false, "UNKNOWN", e.getMessage(), rtt, Instant.now(),
                Map.of("host", host, "port", port));
        }
    }

    private void probeTcp(String host, int port) throws NetException {
        if (host.isBlank() || port <= 0) {
            throw new NetException("host/port 缺失");
        }
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
        } catch (Exception e) {
            throw new NetException("TCP 探活失败 host=" + host + ":" + port + " cause=" + e.getMessage());
        }
    }

    private void probeJdbc(DataSource ds, JsonNode config) throws AuthException, DrvException {
        String url = buildJdbcUrl(ds.getType(), config);
        Properties props = new Properties();
        props.setProperty("user", config.path("username").asText(""));
        props.setProperty("password", config.path("password").asText(""));
        try (Connection c = DriverManager.getConnection(url, props)) {
            try (var stmt = c.createStatement()) {
                stmt.setQueryTimeout(5);
                stmt.executeQuery("SELECT 1");
            }
        } catch (java.sql.SQLInvalidAuthorizationSpecException e) {
            throw new AuthException("鉴权失败: " + e.getMessage());
        } catch (java.sql.SQLException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            if (msg.contains("auth") || msg.contains("password") || msg.contains("denied")
                || msg.contains("access") || msg.contains("permission")) {
                throw new AuthException("鉴权失败: " + e.getMessage());
            }
            throw new DrvException("JDBC 探活失败: " + e.getMessage());
        }
    }

    private boolean isRdbms(DataSourceType type) {
        return type == DataSourceType.MYSQL || type == DataSourceType.POSTGRES
            || type == DataSourceType.ORACLE || type == DataSourceType.SQLSERVER;
    }

    private String buildJdbcUrl(DataSourceType type, JsonNode cfg) {
        String host = cfg.path("host").asText("localhost");
        int port = cfg.path("port").asInt();
        String db = cfg.path("dbName").asText(cfg.path("database").asText(""));
        return switch (type) {
            case MYSQL -> "jdbc:mysql://" + host + ":" + (port == 0 ? 3306 : port) + "/" + db;
            case POSTGRES -> "jdbc:postgresql://" + host + ":" + (port == 0 ? 5432 : port) + "/" + db;
            case ORACLE -> "jdbc:oracle:thin:@" + host + ":" + (port == 0 ? 1521 : port) + ":" + db;
            case SQLSERVER -> "jdbc:sqlserver://" + host + ":" + (port == 0 ? 1433 : port) + ";databaseName=" + db;
            default -> throw new IllegalArgumentException("unsupported rdbms type " + type);
        };
    }

    private static class NetException extends Exception {
        NetException(String m) { super(m); }
    }
    private static class AuthException extends Exception {
        AuthException(String m) { super(m); }
    }
    private static class DrvException extends Exception {
        DrvException(String m) { super(m); }
    }
}
