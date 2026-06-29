package com.onelake.catalog.service;

import com.onelake.catalog.config.TrinoConnectionFactory;
import com.onelake.catalog.domain.entity.Asset;
import com.onelake.catalog.dto.AssetMaintenanceAssessmentDTO;
import com.onelake.catalog.dto.AssetMaintenanceRequest;
import com.onelake.catalog.dto.AssetMaintenanceResultDTO;
import com.onelake.catalog.repository.AssetRepository;
import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class CatalogMaintenanceService {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");

    private final AssetRepository assetRepo;
    private final TrinoConnectionFactory trinoConnectionFactory;

    @Value("${onelake.catalog.maintenance.small-file-threshold-bytes:134217728}")
    private long smallFileThresholdBytes = 134217728L;

    @Value("${onelake.catalog.maintenance.small-file-risk-count:10}")
    private int smallFileRiskCount = 10;

    @Value("${onelake.catalog.maintenance.dwd-freshness-sla-minutes:60}")
    private int dwdFreshnessSlaMinutes = 60;

    @Value("${onelake.catalog.maintenance.default-freshness-sla-minutes:1440}")
    private int defaultFreshnessSlaMinutes = 1440;

    @Transactional(readOnly = true)
    public List<AssetMaintenanceAssessmentDTO> listDwdAssessments() {
        UUID tenantId = requireTenant();
        return assetRepo.findByTenantIdAndLayer(tenantId, "DWD")
            .stream()
            .map(this::assess)
            .toList();
    }

    @Transactional(readOnly = true)
    public AssetMaintenanceAssessmentDTO assess(UUID assetId) {
        return assess(getOwnedAsset(assetId));
    }

    public AssetMaintenanceResultDTO runMaintenance(UUID assetId, AssetMaintenanceRequest request) {
        Asset asset = getOwnedAsset(assetId);
        TableRef table = tableRef(asset.getOmFqn());
        String operation = normalizeOperation(request == null ? null : request.operation());
        String statement = maintenanceStatement(table, operation);
        try (Connection connection = trinoConnectionFactory.getConnection();
             Statement stmt = connection.createStatement()) {
            stmt.execute(statement);
            asset.setSyncedAt(Instant.now());
            assetRepo.save(asset);
            return new AssetMaintenanceResultDTO(
                asset.getId(),
                asset.getOmFqn(),
                operation,
                "SUCCEEDED",
                statement,
                successMessage(operation),
                Instant.now()
            );
        } catch (SQLException e) {
            throw new BizException(50062, "Iceberg 维护任务失败: " + rootMessage(e), e);
        }
    }

    private AssetMaintenanceAssessmentDTO assess(Asset asset) {
        Instant now = Instant.now();
        FileStats files = resolveFileStats(asset.getOmFqn());
        int slaMinutes = slaMinutes(asset);
        Long lagMinutes = asset.getLastSyncAt() == null
            ? null
            : Duration.between(asset.getLastSyncAt(), now).toMinutes();

        List<String> risks = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();
        String freshnessStatus = "UNKNOWN";
        if (lagMinutes == null) {
            risks.add("FRESHNESS_UNKNOWN");
        } else if (lagMinutes > slaMinutes) {
            freshnessStatus = "BREACHED";
            risks.add("FRESHNESS_SLA_BREACHED");
        } else {
            freshnessStatus = "OK";
        }

        if (files == null) {
            risks.add("ICEBERG_METADATA_UNAVAILABLE");
        } else if (files.smallFileCount() >= smallFileRiskCount) {
            risks.add("SMALL_FILE_RISK");
            suggestions.add("OPTIMIZE");
        }

        if (files != null && files.fileCount() > 0) {
            suggestions.add("EXPIRE_SNAPSHOTS");
            suggestions.add("REMOVE_ORPHAN_FILES");
        }

        String status = risks.contains("FRESHNESS_SLA_BREACHED") ? "CRITICAL"
            : risks.isEmpty() ? "OK" : "WARN";
        return new AssetMaintenanceAssessmentDTO(
            asset.getId(),
            asset.getOmFqn(),
            layerOf(asset),
            status,
            freshnessStatus,
            lagMinutes,
            slaMinutes,
            files == null ? null : files.fileCount(),
            files == null ? null : files.smallFileCount(),
            files == null ? null : files.totalBytes(),
            smallFileThresholdBytes,
            smallFileRiskCount,
            risks,
            suggestions.stream().distinct().toList(),
            asset.getLastSyncAt(),
            now
        );
    }

    private FileStats resolveFileStats(String fqn) {
        TableRef table = tableRef(fqn);
        String sql = """
            SELECT
              CAST(count(*) AS integer) AS file_count,
              CAST(coalesce(sum(CASE WHEN file_size_in_bytes < %d THEN 1 ELSE 0 END), 0) AS integer) AS small_file_count,
              CAST(coalesce(sum(file_size_in_bytes), 0) AS bigint) AS total_bytes
            FROM iceberg.%s.%s
            """.formatted(smallFileThresholdBytes, quote(table.schema()), quote(table.table() + "$files"));
        try (Connection connection = trinoConnectionFactory.getConnection();
             Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (!rs.next()) {
                return new FileStats(0, 0, 0L);
            }
            return new FileStats(
                rs.getInt("file_count"),
                rs.getInt("small_file_count"),
                rs.getLong("total_bytes")
            );
        } catch (SQLException e) {
            return null;
        }
    }

    private String maintenanceStatement(TableRef table, String operation) {
        String target = "iceberg.%s.%s".formatted(quote(table.schema()), quote(table.table()));
        return switch (operation) {
            case "OPTIMIZE" -> "ALTER TABLE " + target + " EXECUTE optimize";
            case "EXPIRE_SNAPSHOTS" -> "ALTER TABLE " + target + " EXECUTE expire_snapshots(retention_threshold => '7d')";
            case "REMOVE_ORPHAN_FILES" -> "ALTER TABLE " + target + " EXECUTE remove_orphan_files(retention_threshold => '7d')";
            default -> throw new BizException(40062, "不支持的维护操作: " + operation);
        };
    }

    private String normalizeOperation(String raw) {
        String value = raw == null || raw.isBlank()
            ? "OPTIMIZE"
            : raw.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "OPTIMIZE", "EXPIRE_SNAPSHOTS", "REMOVE_ORPHAN_FILES" -> value;
            default -> throw new BizException(40062, "不支持的维护操作: " + raw);
        };
    }

    private String successMessage(String operation) {
        return switch (operation) {
            case "OPTIMIZE" -> "Iceberg 小文件合并已提交";
            case "EXPIRE_SNAPSHOTS" -> "Iceberg 过期快照清理已提交";
            case "REMOVE_ORPHAN_FILES" -> "Iceberg 孤儿文件清理已提交";
            default -> "Iceberg 维护任务已提交";
        };
    }

    private Asset getOwnedAsset(UUID assetId) {
        Asset asset = assetRepo.findById(assetId)
            .orElseThrow(() -> new BizException(40400, "资产不存在"));
        if (!requireTenant().equals(asset.getTenantId())) {
            throw new BizException(40300, "无权访问该资产");
        }
        return asset;
    }

    private UUID requireTenant() {
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new BizException(40100, "租户上下文缺失");
        }
        return tenantId;
    }

    private int slaMinutes(Asset asset) {
        return "DWD".equalsIgnoreCase(layerOf(asset)) ? dwdFreshnessSlaMinutes : defaultFreshnessSlaMinutes;
    }

    private String layerOf(Asset asset) {
        if (asset.getLayer() != null && !asset.getLayer().isBlank()) {
            return asset.getLayer().toUpperCase(Locale.ROOT);
        }
        String fqn = asset.getOmFqn();
        int dot = fqn == null ? -1 : fqn.indexOf('.');
        return dot > 0 ? fqn.substring(0, dot).toUpperCase(Locale.ROOT) : null;
    }

    private TableRef tableRef(String fqn) {
        if (fqn == null || fqn.isBlank()) {
            throw new BizException(40062, "资产 FQN 不能为空");
        }
        String[] parts = fqn.split("\\.");
        if (parts.length != 2 || !IDENTIFIER.matcher(parts[0]).matches() || !IDENTIFIER.matcher(parts[1]).matches()) {
            throw new BizException(40062, "仅支持 schema.table 形式的 Iceberg 表: " + fqn);
        }
        return new TableRef(parts[0], parts[1]);
    }

    private String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private String rootMessage(SQLException e) {
        Throwable current = e;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? e.getMessage() : current.getMessage();
    }

    private record TableRef(String schema, String table) {}

    private record FileStats(int fileCount, int smallFileCount, long totalBytes) {}
}
