package com.onelake.catalog.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.onelake.catalog.domain.entity.Asset;
import com.onelake.catalog.repository.AssetRepository;
import com.onelake.common.context.TenantContext;
import com.onelake.common.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

@Service
@RequiredArgsConstructor
public class CatalogColumnRefreshService {

    private final AssetRepository assetRepo;

    @Value("${onelake.dataplane.trino.jdbc-url:jdbc:trino://localhost:18080/iceberg}")
    private String trinoJdbcUrl;

    @Value("${onelake.dataplane.trino.user:onelake}")
    private String trinoUser;

    @Value("${onelake.dataplane.trino.password:}")
    private String trinoPassword;

    @Value("${onelake.dataplane.trino.timeout-seconds:30}")
    private int timeoutSeconds;

    @Transactional
    public int refreshMissingColumns() {
        List<Asset> assets = assetRepo.findByTenantId(TenantContext.getTenantId());
        int refreshed = 0;
        for (Asset asset : assets) {
            if (!needsRefresh(asset)) {
                continue;
            }
            List<Map<String, Object>> columns = loadColumns(asset.getOmFqn(), existingColumns(asset.getColumns()));
            if (!columns.isEmpty()) {
                asset.setColumns(JsonUtil.toJson(columns));
                asset.setSyncedAt(Instant.now());
                assetRepo.save(asset);
                refreshed++;
            }
        }
        return refreshed;
    }

    private boolean needsRefresh(Asset asset) {
        if (asset.getOmFqn() == null || asset.getOmFqn().isBlank()) {
            return false;
        }
        if (!"TABLE".equalsIgnoreCase(asset.getAssetType()) && !"VIEW".equalsIgnoreCase(asset.getAssetType())) {
            return false;
        }
        String columns = asset.getColumns();
        if (columns == null || columns.isBlank()) {
            return true;
        }
        try {
            JsonNode node = JsonUtil.parse(columns);
            return !node.isArray() || node.isEmpty();
        } catch (Exception ignored) {
            return true;
        }
    }

    private List<Map<String, Object>> loadColumns(String fqn, Map<String, JsonNode> existing) {
        TableRef table = TableRef.of(fqn);
        if (table == null) {
            return List.of();
        }
        try {
            Class.forName("io.trino.jdbc.TrinoDriver");
            Properties properties = new Properties();
            properties.setProperty("user", trinoUser);
            if (trinoPassword != null && !trinoPassword.isBlank()) {
                properties.setProperty("password", trinoPassword);
            }
            try (var connection = DriverManager.getConnection(trinoJdbcUrl, properties);
                 PreparedStatement statement = connection.prepareStatement("""
                     SELECT column_name, data_type
                     FROM information_schema.columns
                     WHERE table_schema = ? AND table_name = ?
                     ORDER BY ordinal_position
                     """)) {
                statement.setQueryTimeout(Math.min(timeoutSeconds, 15));
                statement.setString(1, table.schema());
                statement.setString(2, table.table());
                try (ResultSet rs = statement.executeQuery()) {
                    List<Map<String, Object>> columns = new ArrayList<>();
                    while (rs.next()) {
                        String name = rs.getString("column_name");
                        JsonNode old = existing.get(name.toLowerCase(Locale.ROOT));
                        Map<String, Object> column = new LinkedHashMap<>();
                        column.put("name", name);
                        column.put("type", rs.getString("data_type"));
                        copyIfPresent(old, column, "description");
                        copyIfPresent(old, column, "classification");
                        copyIfPresent(old, column, "piiType");
                        copyIfPresent(old, column, "suggestLevel");
                        columns.add(column);
                    }
                    return columns;
                }
            }
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private Map<String, JsonNode> existingColumns(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode node = JsonUtil.parse(raw);
            if (!node.isArray()) {
                return Map.of();
            }
            Map<String, JsonNode> columns = new LinkedHashMap<>();
            for (JsonNode column : node) {
                String name = column.path("name").asText("");
                if (!name.isBlank()) {
                    columns.put(name.toLowerCase(Locale.ROOT), column);
                }
            }
            return columns;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private void copyIfPresent(JsonNode old, Map<String, Object> column, String field) {
        if (old == null) {
            return;
        }
        JsonNode value = old.get(field);
        if (value != null && !value.isNull()) {
            column.put(field, value.asText());
        }
    }

    private record TableRef(String schema, String table) {
        private static TableRef of(String fqn) {
            String[] parts = fqn.split("\\.");
            if (parts.length < 2) {
                return null;
            }
            return new TableRef(parts[parts.length - 2], parts[parts.length - 1]);
        }
    }
}
