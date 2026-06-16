package com.onelake.integration.client.discovery;

import com.onelake.common.exception.BizException;
import com.onelake.integration.dto.DiscoveredColumnDTO;

import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

abstract class AbstractJdbcDiscoveryStrategy implements DataSourceDiscoveryStrategy {

    protected List<String> querySingleColumn(String url, Map<String, Object> config, String sql) {
        List<String> values = new ArrayList<>();
        try (var connection = DriverManager.getConnection(url, jdbcProps(config));
             var statement = connection.createStatement()) {
            statement.setQueryTimeout(10);
            try (var rs = statement.executeQuery(sql)) {
                while (rs.next()) {
                    String value = rs.getString(1);
                    if (value != null && !value.isBlank()) {
                        values.add(value);
                    }
                }
            }
            return values;
        } catch (Exception e) {
            throw new BizException(40025, "库列表探查失败: " + e.getMessage());
        }
    }

    protected List<String> queryQualifiedTables(
        String url,
        Map<String, Object> config,
        String sql,
        String schema
    ) {
        List<String> tables = new ArrayList<>();
        try (var connection = DriverManager.getConnection(url, jdbcProps(config));
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(10);
            statement.setString(1, schema);
            statement.setString(2, schema);
            try (var rs = statement.executeQuery()) {
                while (rs.next()) {
                    tables.add(rs.getString(1) + "." + rs.getString(2));
                }
            }
            return tables;
        } catch (Exception e) {
            throw new BizException(40029, "表探查失败: " + e.getMessage());
        }
    }

    protected List<DiscoveredColumnDTO> queryColumns(
        String url,
        Map<String, Object> config,
        String sql,
        TableRef ref
    ) {
        List<DiscoveredColumnDTO> columns = new ArrayList<>();
        try (var connection = DriverManager.getConnection(url, jdbcProps(config));
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(10);
            statement.setString(1, ref.schema());
            statement.setString(2, ref.table());
            try (var rs = statement.executeQuery()) {
                while (rs.next()) {
                    columns.add(new DiscoveredColumnDTO(
                        rs.getString(1),
                        rs.getString(2),
                        "YES".equalsIgnoreCase(rs.getString(3)),
                        "PRI".equalsIgnoreCase(rs.getString(4)),
                        rs.getInt(5)
                    ));
                }
            }
            if (columns.isEmpty()) {
                throw new BizException(40404, "未发现表字段: " + ref.schema() + "." + ref.table());
            }
            return columns;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(40030, "字段探查失败: " + e.getMessage());
        }
    }

    protected Properties jdbcProps(Map<String, Object> config) {
        Properties props = new Properties();
        props.setProperty("user", text(config.get("username")));
        props.setProperty("password", text(config.get("password")));
        return props;
    }

    protected TableRef tableRef(String objectName, String defaultSchema) {
        if (objectName == null || objectName.isBlank()) {
            throw new BizException(40031, "objectName 不能为空");
        }
        String trimmed = objectName.trim();
        int dot = trimmed.lastIndexOf('.');
        if (dot > 0 && dot < trimmed.length() - 1) {
            return new TableRef(trimmed.substring(0, dot), trimmed.substring(dot + 1));
        }
        return new TableRef(defaultSchema, trimmed);
    }

    protected String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    protected int intValue(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return 0;
        }
        return Integer.parseInt(String.valueOf(value));
    }

    protected record TableRef(String schema, String table) {}
}
