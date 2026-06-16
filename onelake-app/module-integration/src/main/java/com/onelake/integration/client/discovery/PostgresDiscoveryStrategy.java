package com.onelake.integration.client.discovery;

import com.onelake.integration.domain.enums.DataSourceType;
import com.onelake.integration.dto.DiscoveredColumnDTO;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class PostgresDiscoveryStrategy extends AbstractJdbcDiscoveryStrategy {

    private static final String DATABASE_SQL =
        "SELECT datname FROM pg_database WHERE datallowconn = true AND datistemplate = false ORDER BY datname";

    private static final String SCHEMA_SQL = "SELECT schema_name FROM information_schema.schemata " +
        "WHERE schema_name NOT IN ('information_schema','pg_catalog','pg_toast') " +
        "ORDER BY schema_name";

    private static final String TABLE_SQL = "SELECT table_schema, table_name FROM information_schema.tables " +
        "WHERE table_type = 'BASE TABLE' " +
        "AND (? = '' OR table_schema = ?) " +
        "AND table_schema NOT IN ('information_schema','pg_catalog') " +
        "ORDER BY table_schema, table_name";

    private static final String COLUMN_SQL = "SELECT c.column_name, c.data_type, c.is_nullable, " +
        "CASE WHEN tc.constraint_type = 'PRIMARY KEY' THEN 'PRI' ELSE '' END AS column_key, " +
        "c.ordinal_position " +
        "FROM information_schema.columns c " +
        "LEFT JOIN information_schema.key_column_usage kcu " +
        "ON c.table_schema = kcu.table_schema AND c.table_name = kcu.table_name AND c.column_name = kcu.column_name " +
        "LEFT JOIN information_schema.table_constraints tc " +
        "ON kcu.constraint_schema = tc.constraint_schema AND kcu.constraint_name = tc.constraint_name " +
        "WHERE c.table_schema = ? AND c.table_name = ? " +
        "ORDER BY c.ordinal_position";

    @Override
    public DataSourceType type() {
        return DataSourceType.POSTGRES;
    }

    @Override
    public List<String> discover(Map<String, Object> config) {
        return querySingleColumn(jdbcUrl(config), config, DATABASE_SQL);
    }

    @Override
    public List<String> listSchemas(Map<String, Object> config) {
        return querySingleColumn(jdbcUrl(config), config, SCHEMA_SQL);
    }

    @Override
    public List<String> listTables(Map<String, Object> config, String schema) {
        return queryQualifiedTables(jdbcUrl(config), config, TABLE_SQL, normalizeSchema(schema));
    }

    @Override
    public List<DiscoveredColumnDTO> describeTable(Map<String, Object> config, String objectName) {
        return queryColumns(jdbcUrl(config), config, COLUMN_SQL, tableRef(objectName, "public"));
    }

    private String jdbcUrl(Map<String, Object> config) {
        String host = text(config.getOrDefault("host", "localhost"));
        int port = intValue(config.get("port"));
        String dbName = text(config.get("dbName"));
        if (dbName.isBlank()) {
            dbName = text(config.get("database"));
        }
        return "jdbc:postgresql://" + host + ":" + (port == 0 ? 5432 : port) + "/" +
            (dbName.isBlank() ? "postgres" : dbName);
    }

    private String normalizeSchema(String schema) {
        return schema == null || schema.isBlank() ? "" : schema.trim();
    }
}
