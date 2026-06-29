package com.onelake.catalog.service.sql;

import com.fasterxml.jackson.databind.JsonNode;
import com.onelake.catalog.domain.entity.Asset;
import com.onelake.catalog.repository.AssetRepository;
import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.sql.ReadOnlySqlValidator;
import com.onelake.common.util.JsonUtil;
import com.onelake.security.service.SecurityService;
import lombok.RequiredArgsConstructor;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SqlAssetSecurityService {

    private final AssetRepository assetRepo;
    private final SecurityService securityService;

    public SqlAssetSecurityContext validateAndPlan(
        String sql,
        int missingAssetCode,
        String missingAssetMessagePrefix
    ) {
        Statement statement = ReadOnlySqlValidator.requireSingleReadOnlyStatement(
            sql,
            missingAssetCode,
            "SQL 仅允许只读查询",
            "SQL 不允许一次提交多条语句"
        );
        Set<String> referencedTables = ReadOnlySqlValidator.referencedTables(statement);
        if (referencedTables.isEmpty()) {
            return SqlAssetSecurityContext.empty();
        }

        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        Set<String> grantRequired = new LinkedHashSet<>();
        Map<String, SecurityService.FieldProtection> protections = new LinkedHashMap<>();

        Set<String> resolvedTables = new LinkedHashSet<>();
        for (String table : referencedTables) {
            Asset asset = findAsset(tenantId, table)
                .orElseThrow(() -> new BizException(missingAssetCode, missingAssetMessagePrefix + table));
            resolvedTables.add(asset.getOmFqn());
            if (asset.getOwnerId() == null || !asset.getOwnerId().equals(userId)) {
                grantRequired.add(asset.getOmFqn());
            }
            collectColumnProtections(asset, protections);
        }
        collectAliasProtections(statement, protections);

        securityService.requireQueryAccess(grantRequired);
        return new SqlAssetSecurityContext(resolvedTables, protections);
    }

    private Optional<Asset> findAsset(UUID tenantId, String table) {
        Optional<Asset> exact = assetRepo.findByTenantIdAndOmFqn(tenantId, table);
        if (exact.isPresent()) {
            return exact;
        }
        String normalized = normalizeTableFqn(table);
        if (normalized.equals(table)) {
            return Optional.empty();
        }
        return assetRepo.findByTenantIdAndOmFqn(tenantId, normalized);
    }

    private String normalizeTableFqn(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String value = raw.trim().replace("\"", "").replace("`", "");
        String[] parts = value.split("\\.");
        if (parts.length >= 3) {
            String catalog = parts[0].toLowerCase(Locale.ROOT);
            if ("iceberg".equals(catalog) || "onelake".equals(catalog) || "hive".equals(catalog)) {
                return parts[parts.length - 2] + "." + parts[parts.length - 1];
            }
        }
        return value;
    }

    private void collectAliasProtections(
        Statement statement,
        Map<String, SecurityService.FieldProtection> protections
    ) {
        if (!(statement instanceof Select select)) {
            return;
        }
        PlainSelect plainSelect = select.getPlainSelect();
        if (plainSelect == null || plainSelect.getSelectItems() == null) {
            return;
        }
        for (SelectItem<?> item : plainSelect.getSelectItems()) {
            if (item.getAlias() == null || item.getExpression() == null) {
                continue;
            }
            if (item.getExpression() instanceof Column column) {
                String source = normalizeColumnName(column.getColumnName());
                String alias = normalizeColumnName(item.getAlias().getName());
                SecurityService.FieldProtection protection = protections.get(source);
                if (protection != null && alias != null) {
                    protections.putIfAbsent(alias, protection);
                }
            }
        }
    }

    private void collectColumnProtections(
        Asset asset,
        Map<String, SecurityService.FieldProtection> protections
    ) {
        String raw = asset.getColumns();
        if (raw == null || raw.isBlank()) {
            return;
        }
        JsonNode columns;
        try {
            columns = JsonUtil.parse(raw);
        } catch (Exception ignored) {
            return;
        }
        if (!columns.isArray()) {
            return;
        }
        for (JsonNode column : columns) {
            String name = column.path("name").asText("");
            if (name.isBlank()) {
                continue;
            }
            String key = normalizeColumnName(name);
            SecurityService.FieldProtection protection = new SecurityService.FieldProtection(
                asset.getOmFqn() + "." + name,
                textOrNull(column.path("classification").asText(null)),
                textOrNull(column.path("piiType").asText(null)),
                textOrNull(column.path("suggestLevel").asText(null))
            );
            protections.merge(key, protection, this::mergeProtection);
        }
    }

    private SecurityService.FieldProtection mergeProtection(
        SecurityService.FieldProtection left,
        SecurityService.FieldProtection right
    ) {
        List<String> targets = new java.util.ArrayList<>(left.targetFqns());
        for (String target : right.targetFqns()) {
            if (!targets.contains(target)) {
                targets.add(target);
            }
        }
        return new SecurityService.FieldProtection(
            left.targetFqn(),
            strongestLevel(left.classification(), right.classification()),
            firstText(left.piiType(), right.piiType()),
            strongestLevel(left.suggestLevel(), right.suggestLevel()),
            targets
        );
    }

    private String strongestLevel(String left, String right) {
        int leftRank = levelRank(left);
        int rightRank = levelRank(right);
        if (leftRank == 0 && rightRank == 0) {
            return firstText(left, right);
        }
        return rightRank > leftRank ? right : left;
    }

    private int levelRank(String level) {
        if (level == null) {
            return 0;
        }
        return switch (level.trim().toUpperCase()) {
            case "L1" -> 1;
            case "L2" -> 2;
            case "L3" -> 3;
            case "L4" -> 4;
            default -> 0;
        };
    }

    private String firstText(String left, String right) {
        return textOrNull(left) != null ? textOrNull(left) : textOrNull(right);
    }

    private String textOrNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String normalizeColumnName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String normalized = name.trim();
        if ((normalized.startsWith("\"") && normalized.endsWith("\""))
            || (normalized.startsWith("`") && normalized.endsWith("`"))) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized.toLowerCase();
    }

    public record SqlAssetSecurityContext(
        Collection<String> referencedTables,
        Map<String, SecurityService.FieldProtection> protectionsByColumn
    ) {
        public static SqlAssetSecurityContext empty() {
            return new SqlAssetSecurityContext(Set.of(), Map.of());
        }
    }
}
