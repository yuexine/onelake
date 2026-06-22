package com.onelake.catalog.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.onelake.catalog.domain.entity.LineageEdge;
import com.onelake.catalog.dto.AssetDTO;
import com.onelake.catalog.dto.AssetDetailDTO;
import com.onelake.catalog.repository.LineageEdgeRepository;
import com.onelake.common.context.TenantContext;
import com.onelake.common.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CatalogAssetDetailService {

    private final CatalogService catalogService;
    private final LineageEdgeRepository lineageRepo;
    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public AssetDetailDTO getDetail(UUID id) {
        UUID tenantId = TenantContext.getTenantId();
        AssetDTO asset = catalogService.getAsset(id);
        String fqn = asset.fqn();
        return new AssetDetailDTO(
            asset,
            lineage(tenantId, fqn),
            quality(tenantId, fqn, asset.qualityScore()),
            security(tenantId, fqn, asset.classification(), asset.columns()),
            subscription(tenantId, fqn, asset.popularity())
        );
    }

    private AssetDetailDTO.LineageSummaryDTO lineage(UUID tenantId, String fqn) {
        List<AssetDetailDTO.LineageEdgeDTO> upstream = lineageRepo.findByTenantIdAndDownstreamFqn(tenantId, fqn)
            .stream().map(this::toLineageDTO).toList();
        List<AssetDetailDTO.LineageEdgeDTO> downstream = lineageRepo.findByTenantIdAndUpstreamFqn(tenantId, fqn)
            .stream().map(this::toLineageDTO).toList();
        return new AssetDetailDTO.LineageSummaryDTO(upstream, downstream, catalogService.downstream(tenantId, fqn));
    }

    private AssetDetailDTO.LineageEdgeDTO toLineageDTO(LineageEdge edge) {
        return new AssetDetailDTO.LineageEdgeDTO(
            edge.getUpstreamFqn(),
            edge.getDownstreamFqn(),
            parseColumnLineage(edge.getColumnLevel()),
            edge.getJobRef(),
            edge.getSyncedAt()
        );
    }

    private List<AssetDetailDTO.ColumnLineageDTO> parseColumnLineage(String raw) {
        if (raw == null || raw.isBlank()) {
            return Collections.emptyList();
        }
        try {
            JsonNode node = JsonUtil.parse(raw);
            if (!node.isArray()) {
                return Collections.emptyList();
            }
            List<AssetDetailDTO.ColumnLineageDTO> columns = new ArrayList<>();
            node.forEach(item -> {
                String from = item.path("from").asText("");
                String to = item.path("to").asText("");
                if (from.isBlank() && to.isBlank()) {
                    return;
                }
                columns.add(new AssetDetailDTO.ColumnLineageDTO(
                    from,
                    to,
                    item.path("transform").asText(null)
                ));
            });
            return columns;
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    private AssetDetailDTO.QualitySummaryDTO quality(UUID tenantId, String fqn, BigDecimal assetScore) {
        List<AssetDetailDTO.QualityRuleStatusDTO> rules = jdbc.query("""
            SELECT DISTINCT ON (r.rule_type, coalesce(r.target_column, ''))
              r.id,
              r.rule_type,
              r.target_column,
              r.severity,
              rr.passed,
              rr.pass_rate,
              rr.failed_rows,
              rr.checked_at
            FROM quality.rule r
            LEFT JOIN LATERAL (
              SELECT passed, pass_rate, failed_rows, checked_at
              FROM quality.run_result
              WHERE rule_id = r.id
              ORDER BY checked_at DESC
              LIMIT 1
            ) rr ON TRUE
            WHERE r.tenant_id = ? AND r.target_fqn = ?
            ORDER BY r.rule_type, coalesce(r.target_column, ''), rr.checked_at DESC NULLS LAST, r.created_at DESC
            """, (rs, rowNum) -> new AssetDetailDTO.QualityRuleStatusDTO(
                rs.getObject("id", UUID.class),
                rs.getString("rule_type"),
                rs.getString("target_column"),
                rs.getString("severity"),
                (Boolean) rs.getObject("passed"),
                rs.getBigDecimal("pass_rate"),
                rs.getObject("failed_rows") == null ? null : rs.getLong("failed_rows"),
                instant(rs, "checked_at")
            ), tenantId, fqn);

        int failed = (int) rules.stream().filter(rule -> Boolean.FALSE.equals(rule.passed())).count();
        Instant latestCheckedAt = rules.stream()
            .map(AssetDetailDTO.QualityRuleStatusDTO::checkedAt)
            .filter(java.util.Objects::nonNull)
            .max(Instant::compareTo)
            .orElse(null);
        BigDecimal score = rules.stream()
            .map(AssetDetailDTO.QualityRuleStatusDTO::passRate)
            .filter(java.util.Objects::nonNull)
            .min(BigDecimal::compareTo)
            .orElse(assetScore);

        return new AssetDetailDTO.QualitySummaryDTO(score, rules.size(), failed, latestCheckedAt, rules);
    }

    private AssetDetailDTO.SecuritySummaryDTO security(
        UUID tenantId,
        String fqn,
        String classification,
        List<AssetDTO.AssetColumnDTO> columns
    ) {
        int sensitiveColumns = (int) columns.stream()
            .filter(c -> isSensitive(c.classification()) || isSensitive(c.suggestLevel()))
            .count();
        return new AssetDetailDTO.SecuritySummaryDTO(
            classification,
            sensitiveColumns,
            count("""
                SELECT COUNT(*)
                FROM security.access_grant
                WHERE tenant_id = ?
                  AND asset_fqn = ?
                  AND status = 'ACTIVE'
                  AND (expires_at IS NULL OR expires_at > now())
                """, tenantId, fqn),
            count("""
                SELECT COUNT(*)
                FROM security.masking_policy
                WHERE tenant_id = ?
                  AND (target_fqn = ? OR target_fqn LIKE ?)
                """, tenantId, fqn, fqn + ".%"),
            count("""
                SELECT COUNT(*)
                FROM security.pii_scan_record
                WHERE tenant_id = ?
                  AND (fqn = ? OR fqn LIKE ?)
                  AND status <> 'IGNORED'
                """, tenantId, fqn, fqn + ".%")
        );
    }

    private boolean isSensitive(String level) {
        return "L3".equalsIgnoreCase(level) || "L4".equalsIgnoreCase(level);
    }

    private AssetDetailDTO.SubscriptionSummaryDTO subscription(UUID tenantId, String fqn, Integer popularity) {
        Integer apiCount = count("""
            SELECT COUNT(*)
            FROM dataservice.api_definition
            WHERE tenant_id = ? AND source_fqn = ?
            """, tenantId, fqn);
        Integer publishedApiCount = count("""
            SELECT COUNT(*)
            FROM dataservice.api_definition
            WHERE tenant_id = ? AND source_fqn = ? AND status = 'PUBLISHED'
            """, tenantId, fqn);
        Integer approvedSubscriptions = count("""
            SELECT COUNT(*)
            FROM dataservice.api_definition api
            JOIN dataservice.subscription sub ON sub.api_id = api.id
            WHERE api.tenant_id = ? AND api.source_fqn = ? AND sub.status = 'APPROVED'
            """, tenantId, fqn);
        Long callCount = countLong("""
            SELECT COUNT(*)
            FROM dataservice.api_definition api
            JOIN dataservice.api_call_log log ON log.api_id = api.id
            WHERE api.tenant_id = ? AND api.source_fqn = ?
            """, tenantId, fqn);
        return new AssetDetailDTO.SubscriptionSummaryDTO(
            apiCount,
            publishedApiCount,
            approvedSubscriptions,
            callCount,
            popularity == null ? approvedSubscriptions : popularity
        );
    }

    private Integer count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private Long countLong(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    private Instant instant(ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
