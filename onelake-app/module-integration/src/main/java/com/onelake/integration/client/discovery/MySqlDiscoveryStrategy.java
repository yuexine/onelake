package com.onelake.integration.client.discovery;

import com.onelake.integration.domain.enums.DataSourceType;
import com.onelake.integration.dto.DiscoveredColumnDTO;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class MySqlDiscoveryStrategy extends AbstractJdbcDiscoveryStrategy {

    private static final String SCHEMA_SQL = "SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA " +
        "WHERE SCHEMA_NAME NOT IN ('information_schema','performance_schema','mysql','sys') " +
        "ORDER BY SCHEMA_NAME";

    private static final String TABLE_SQL = "SELECT table_schema, table_name FROM information_schema.tables " +
        "WHERE table_type = 'BASE TABLE' " +
        "AND (? = '' OR table_schema = ?) " +
        "AND table_schema NOT IN ('information_schema','performance_schema','mysql','sys') " +
        "ORDER BY table_schema, table_name";

    private static final String COLUMN_SQL = "SELECT column_name, column_type, is_nullable, column_key, ordinal_position " +
        "FROM information_schema.columns " +
        "WHERE table_schema = ? AND table_name = ? " +
        "ORDER BY ordinal_position";

    @Override
    public DataSourceType type() {
        return DataSourceType.MYSQL;
    }

    @Override
    public List<String> discover(Map<String, Object> config) {
        return querySingleColumn(jdbcUrl(config), config, SCHEMA_SQL);
    }

    @Override
    public List<String> listSchemas(Map<String, Object> config) {
        return discover(config);
    }

    @Override
    public List<String> listTables(Map<String, Object> config, String schema) {
        return queryQualifiedTables(jdbcUrl(config), config, TABLE_SQL, normalizeSchema(config, schema));
    }

    @Override
    public List<DiscoveredColumnDTO> describeTable(Map<String, Object> config, String objectName) {
        return queryColumns(jdbcUrl(config), config, COLUMN_SQL, tableRef(objectName, defaultSchema(config)));
    }

    private String jdbcUrl(Map<String, Object> config) {
        String host = text(config.getOrDefault("host", "localhost"));
        int port = intValue(config.get("port"));
        return "jdbc:mysql://" + host + ":" + (port == 0 ? 3306 : port) + "/";
    }

    private String normalizeSchema(Map<String, Object> config, String schema) {
        if (schema != null && !schema.isBlank()) {
            return schema.trim();
        }
        return defaultSchema(config);
    }

    private String defaultSchema(Map<String, Object> config) {
        return text(config.getOrDefault("dbName", config.get("database")));
    }
}
