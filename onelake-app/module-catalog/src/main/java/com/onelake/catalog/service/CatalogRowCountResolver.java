package com.onelake.catalog.service;

import com.onelake.catalog.config.TrinoConnectionFactory;
import com.onelake.catalog.domain.entity.Asset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class CatalogRowCountResolver {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final TrinoConnectionFactory trinoConnectionFactory;

    @Value("${onelake.catalog.live-row-count.enabled:true}")
    private boolean enabled;

    @Value("${onelake.catalog.live-row-count.max-assets:50}")
    private int maxAssets;

    @Value("${onelake.catalog.live-row-count.timeout-seconds:10}")
    private int timeoutSeconds;

    public Map<UUID, Long> resolve(Collection<Asset> assets) {
        if (!enabled || assets == null || assets.isEmpty() || maxAssets <= 0) {
            return Map.of();
        }

        Map<UUID, TableRef> refs = new LinkedHashMap<>();
        for (Asset asset : assets) {
            if (asset == null || asset.getId() == null || !isQueryableTable(asset)) {
                continue;
            }
            TableRef ref = TableRef.of(asset.getOmFqn());
            if (ref == null) {
                continue;
            }
            refs.put(asset.getId(), ref);
            if (refs.size() >= maxAssets) {
                break;
            }
        }
        if (refs.isEmpty()) {
            return Map.of();
        }

        Map<UUID, Long> rowCounts = new LinkedHashMap<>();
        try (Connection connection = trinoConnectionFactory.getConnection();
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(Math.max(1, timeoutSeconds));
            for (Map.Entry<UUID, TableRef> entry : refs.entrySet()) {
                try (ResultSet rs = statement.executeQuery("SELECT count(*) FROM " + entry.getValue().sqlName())) {
                    if (rs.next()) {
                        rowCounts.put(entry.getKey(), rs.getLong(1));
                    }
                } catch (Exception e) {
                    log.debug("Skip live row count for {}: {}", entry.getValue().sqlName(), rootMessage(e));
                }
            }
        } catch (Exception e) {
            log.debug("Live row count refresh skipped: {}", rootMessage(e));
            return Map.of();
        }
        return rowCounts;
    }

    private boolean isQueryableTable(Asset asset) {
        String type = asset.getAssetType();
        return "TABLE".equalsIgnoreCase(type) || "VIEW".equalsIgnoreCase(type);
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private record TableRef(String schema, String table) {
        static TableRef of(String fqn) {
            if (fqn == null || fqn.isBlank()) {
                return null;
            }
            String[] parts = fqn.trim().split("\\.");
            if (parts.length < 2) {
                return null;
            }
            String schema = parts[parts.length - 2];
            String table = parts[parts.length - 1];
            if (!IDENTIFIER.matcher(schema).matches() || !IDENTIFIER.matcher(table).matches()) {
                return null;
            }
            return new TableRef(schema, table);
        }

        String sqlName() {
            return schema + "." + table;
        }
    }
}
