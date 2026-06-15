package com.onelake.integration.client.discovery;

import com.onelake.common.exception.BizException;
import com.onelake.integration.domain.enums.DataSourceType;
import org.springframework.stereotype.Component;

import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

@Component
public class DatabaseDiscoveryClient {

    public List<String> discover(DataSourceType type, Map<String, Object> config) {
        return switch (type) {
            case MYSQL -> queryDatabases(jdbcUrl(type, config), config,
                "SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA " +
                    "WHERE SCHEMA_NAME NOT IN ('information_schema','performance_schema','mysql','sys') " +
                    "ORDER BY SCHEMA_NAME");
            case POSTGRES -> queryDatabases(jdbcUrl(type, config), config,
                "SELECT datname FROM pg_database WHERE datallowconn = true AND datistemplate = false ORDER BY datname");
            default -> throw new BizException(40024, "当前类型暂不支持库列表探查，请手动输入");
        };
    }

    private List<String> queryDatabases(String url, Map<String, Object> config, String sql) {
        Properties props = new Properties();
        props.setProperty("user", text(config.get("username")));
        props.setProperty("password", text(config.get("password")));
        List<String> databases = new ArrayList<>();
        try (var connection = DriverManager.getConnection(url, props);
             var statement = connection.createStatement()) {
            statement.setQueryTimeout(10);
            try (var rs = statement.executeQuery(sql)) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    if (name != null && !name.isBlank()) {
                        databases.add(name);
                    }
                }
            }
            return databases;
        } catch (Exception e) {
            throw new BizException(40025, "库列表探查失败: " + e.getMessage());
        }
    }

    private String jdbcUrl(DataSourceType type, Map<String, Object> config) {
        String host = text(config.getOrDefault("host", "localhost"));
        int port = intValue(config.get("port"));
        String dbName = text(config.get("dbName"));
        if (dbName.isBlank()) {
            dbName = text(config.get("database"));
        }
        return switch (type) {
            case MYSQL -> "jdbc:mysql://" + host + ":" + (port == 0 ? 3306 : port) + "/";
            case POSTGRES -> "jdbc:postgresql://" + host + ":" + (port == 0 ? 5432 : port) + "/" +
                (dbName.isBlank() ? "postgres" : dbName);
            default -> throw new BizException(40024, "当前类型暂不支持库列表探查，请手动输入");
        };
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private int intValue(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return 0;
        }
        return Integer.parseInt(String.valueOf(value));
    }
}
