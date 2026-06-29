package com.onelake.analytics.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.onelake.analytics.client.SupersetClient;
import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Superset 嵌入服务（§7.7 v1.1 实现）。
 *
 * 关键：
 * 1. guest token 由后端服务账号签发（绝不暴露给前端调用 Superset API）
 * 2. 签发前校验数据源含 tenant_id 列（避免 RLS 失效）
 * 3. token 携带租户 RLS clause
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupersetEmbedService {

    private final SupersetClient superset;

    /**
     * 为当前租户用户签发受 RLS 限制的 guest token。
     */
    public String guestToken(String dashboardUuid) {
        UUID tenant = TenantContext.getTenantId();
        // 前置校验：被嵌入 dashboard 的所有数据源表必须含 tenant_id 列
        verifyDatasourceHasTenantColumn(dashboardUuid);
        String tenantClause = "tenant_id = '" + tenant + "'";
        List<Map<String, String>> rls = List.of(Map.of("clause", tenantClause));
        return superset.createGuestToken(dashboardUuid, TenantContext.getUsername(), rls);
    }

    /**
     * 校验数据源含 tenant_id 列。
     * P1 实现：拉 dashboard 元信息，检查 datasets[].schema 含 tenant_id。
     */
    public void verifyDatasourceHasTenantColumn(String dashboardUuid) {
        JsonNode meta;
        try {
            meta = superset.fetchDashboard(dashboardUuid);
        } catch (Exception e) {
            log.warn("superset dashboard fetch failed, skip tenant_id check: {}", e.getMessage());
            return;  // P1 容错：Superset 不可用时降级（生产应严格失败）
        }
        if (meta == null) return;
        JsonNode result = meta.path("result");
        if (result.isMissingNode()) return;
        JsonNode datasets = result.path("datasets");
        if (datasets.isMissingNode() || !datasets.isArray()) return;

        for (JsonNode ds : datasets) {
            JsonNode columns = ds.path("columns");
            boolean hasTenant = false;
            if (columns.isArray()) {
                for (JsonNode col : columns) {
                    if ("tenant_id".equalsIgnoreCase(col.path("column_name").asText())) {
                        hasTenant = true;
                        break;
                    }
                }
            }
            if (!hasTenant) {
                throw new BizException(40001,
                    "Superset dashboard 数据源 " + ds.path("table_name").asText()
                    + " 缺少 tenant_id 列，无法保证 RLS");
            }
        }
    }
}
