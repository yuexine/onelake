package com.onelake.catalog.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.trino.jdbc.TrinoDriver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Trino JDBC 连接池配置。
 *
 * 为什么需要连接池：
 *   - DriverManager.getConnection 每次都走完整握手 + TLS + 认证，延迟 50-200ms
 *   - SqlWorkbenchService 每次查询（含 EXPLAIN 估算）都开连接，交互式场景下累积明显
 *   - 多用户并发时无连接复用会放大 Trino coordinator 的握手开销
 *
 * 注意：Trino JDBC 驱动不是纯 JDBC —— QueryStats 订阅、cancel 等通过 Statement 上的
 * TrinoStatement unwrap 实现。这些不依赖连接池特性，HikariCP 只负责 connection 复用。
 */
@Configuration
@ConditionalOnClass(HikariDataSource.class)
public class TrinoDataSourceConfig {

    @Bean(destroyMethod = "close")
    public TrinoConnectionFactory trinoConnectionFactory(
        @Value("${onelake.dataplane.trino.jdbc-url:jdbc:trino://localhost:18080/iceberg}") String jdbcUrl,
        @Value("${onelake.dataplane.trino.user:onelake}") String user,
        @Value("${onelake.dataplane.trino.password:}") String password,
        @Value("${onelake.dataplane.trino.pool.max-size:10}") int maxPoolSize,
        @Value("${onelake.dataplane.trino.pool.min-idle:2}") int minIdle,
        @Value("${onelake.dataplane.trino.pool.connection-timeout-ms:5000}") int connectionTimeoutMs,
        @Value("${onelake.dataplane.trino.pool.idle-timeout-ms:600000}") int idleTimeoutMs,
        @Value("${onelake.dataplane.trino.pool.max-lifetime-ms:1800000}") int maxLifetimeMs
    ) {
        ensureDriverRegistered();
        HikariConfig config = new HikariConfig();
        config.setPoolName("trino-workbench-pool");
        config.setDriverClassName("io.trino.jdbc.TrinoDriver");
        config.setJdbcUrl(jdbcUrl);
        Properties props = new Properties();
        props.setProperty("user", user);
        if (password != null && !password.isBlank()) {
            props.setProperty("password", password);
        }
        config.setDataSourceProperties(props);
        config.setMaximumPoolSize(maxPoolSize);
        config.setMinimumIdle(minIdle);
        config.setConnectionTimeout(connectionTimeoutMs);
        config.setIdleTimeout(idleTimeoutMs);
        config.setMaxLifetime(maxLifetimeMs);
        config.setAutoCommit(true);
        // Trino 不支持事务、不支持 setAutoCommit(false)，保持默认 true
        config.setConnectionTestQuery("SELECT 1");
        return new TrinoConnectionFactory(new HikariDataSource(config));
    }

    private static void ensureDriverRegistered() {
        try {
            Driver registered = DriverManager.getDriver("jdbc:trino://x");
            if (registered == null) {
                DriverManager.registerDriver(new TrinoDriver());
            }
        } catch (SQLException e) {
            try {
                DriverManager.registerDriver(new TrinoDriver());
            } catch (SQLException ex) {
                throw new IllegalStateException("Failed to register TrinoDriver", ex);
            }
        }
    }
}
