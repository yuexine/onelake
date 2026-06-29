package com.onelake.analytics.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.onelake.analytics.client.SupersetClient;
import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SupersetEmbedService 单元测试 —— 覆盖 v1.1 §5.3 Superset 数据源前提：
 * 1. 数据源含 tenant_id 列 → 正常签发 guest token（带 RLS clause）
 * 2. 数据源缺 tenant_id 列 → 拒绝签发（避免 RLS 失效）
 * 3. Superset 不可达 → 容错降级（P1 阶段不严格失败）
 */
class SupersetEmbedServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SupersetClient superset;
    private SupersetEmbedService service;

    @BeforeEach
    void setUp() {
        superset = mock(SupersetClient.class);
        service = new SupersetEmbedService(superset);
        TenantContext.setTenantId(TENANT_ID);
        TenantContext.setUsername("analyst-1");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void guestToken_datasourceWithTenantId_signsTokenWithRlsClause() {
        String uuid = "abc-123";
        when(superset.fetchDashboard(eq(uuid))).thenReturn(buildDashboardMeta(/* hasTenantId */ true));
        when(superset.createGuestToken(eq(uuid), anyString(), anyList())).thenReturn("guest-jwt-token");

        String token = service.guestToken(uuid);

        assertThat(token).isEqualTo("guest-jwt-token");
        // 关键：RLS clause 包含当前租户 ID
        verify(superset).createGuestToken(
            eq(uuid), eq("analyst-1"),
            org.mockito.ArgumentMatchers.argThat(rls -> {
                @SuppressWarnings("unchecked")
                java.util.List<java.util.Map<String, String>> list =
                    (java.util.List<java.util.Map<String, String>>) rls;
                return list.size() == 1
                    && list.get(0).get("clause").equals("tenant_id = '" + TENANT_ID + "'");
            }));
    }

    @Test
    void guestToken_datasourceMissingTenantId_throwsBizException() {
        String uuid = "abc-456";
        when(superset.fetchDashboard(eq(uuid))).thenReturn(buildDashboardMeta(/* hasTenantId */ false));

        assertThatThrownBy(() -> service.guestToken(uuid))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("缺少 tenant_id 列");

        verify(superset, never()).createGuestToken(anyString(), anyString(), anyList());
    }

    @Test
    void guestToken_supersetUnreachable_doesNotFailHard() {
        // P1 容错策略：Superset 不可达时不严格失败
        when(superset.fetchDashboard(anyString())).thenThrow(new RuntimeException("superset offline"));
        when(superset.createGuestToken(anyString(), anyString(), anyList())).thenReturn("fallback-token");

        String token = service.guestToken("any");
        // 降级路径下仍签发 token（生产建议改严格失败，但 P1 不阻断嵌入流程）
        assertThat(token).isEqualTo("fallback-token");
    }

    // ============ helpers ============

    /**
     * 构造 Superset dashboard 元信息 JSON。
     * hasTenantId 控制 datasets[0].columns 是否含 tenant_id 列。
     */
    private JsonNode buildDashboardMeta(boolean hasTenantId) {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode result = root.putObject("result");
        ArrayNode datasets = result.putArray("datasets");
        ObjectNode ds = datasets.addObject();
        ds.put("table_name", "iceberg.dwd.dwd_user");
        ArrayNode cols = ds.putArray("columns");
        addColumn(cols, "user_id", "varchar");
        addColumn(cols, "name", "varchar");
        if (hasTenantId) {
            addColumn(cols, "tenant_id", "uuid");
        }
        return root;
    }

    private void addColumn(ArrayNode cols, String name, String type) {
        ObjectNode c = cols.addObject();
        c.put("column_name", name);
        c.put("type", type);
    }
}
