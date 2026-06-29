package com.onelake.analytics.client;

import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import io.trino.jdbc.TrinoDriver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Trino 查询客户端（驱动而非重造查询引擎）。
 *
 * 使用 io.trino:trino-jdbc 直查 Iceberg 表。X-Trace-Id 通过 Connection clientInfo 透传。
 * P1 单连接查询；后续可接入 Trino 连接池（参考 module-catalog 的 SqlWorkbenchService）。
 */
@Slf4j
@Component
public class TrinoQueryClient {

    private final String jdbcUrl;
    private final String user;
    private final String password;

    public TrinoQueryClient(
            @Value("${onelake.dataplane.trino.jdbc-url:jdbc:trino://localhost:18080/iceberg}") String jdbcUrl,
            @Value("${onelake.dataplane.trino.user:onelake}") String user,
            @Value("${onelake.dataplane.trino.password:}") String password) {
        this.jdbcUrl = jdbcUrl;
        this.user = user;
        this.password = password;
        try {
            DriverManager.registerDriver(new TrinoDriver());
        } catch (SQLException e) {
            throw new BizException(50010, "注册 TrinoDriver 失败", e);
        }
    }

    /**
     * 执行查询，返回 List<Map<field,value>>。
     */
    public List<Map<String, Object>> query(String sql) {
        long start = System.currentTimeMillis();
        try (Connection conn = openConnection();
             Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery(sql)) {
                List<Map<String, Object>> rows = new ArrayList<>();
                ResultSetMetaData meta = rs.getMetaData();
                int n = meta.getColumnCount();
                int rowLimit = 100_000;  // 兜底，与 SqlBuilder LIMIT 配合
                while (rs.next() && rows.size() < rowLimit) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= n; i++) {
                        row.put(meta.getColumnLabel(i), rs.getObject(i));
                    }
                    rows.add(row);
                }
                log.debug("trino query took {}ms, rows={}, sql_md5_len={}",
                    System.currentTimeMillis() - start, rows.size(), sql.length());
                return rows;
            }
        } catch (SQLException e) {
            log.error("trino query failed: {}", e.getMessage());
            throw new BizException(50010, "Trino 查询失败：" + e.getMessage(), e);
        }
    }

    private Connection openConnection() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", user);
        if (password != null && !password.isBlank()) {
            props.setProperty("password", password);
        }
        // 透传 traceId 与 tenant
        String traceId = TenantContext.getTraceId();
        if (traceId != null) {
            props.setProperty("clientInfo", "onelake:" + traceId);
        }
        return DriverManager.getConnection(jdbcUrl, props);
    }
}
