package com.onelake.catalog.service;

import com.onelake.catalog.config.TrinoConnectionFactory;
import com.onelake.catalog.domain.entity.Asset;
import com.onelake.catalog.dto.SchemaChangeExecutionResultDTO;
import com.onelake.catalog.repository.AssetRepository;
import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.util.JsonUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CatalogSchemaChangeServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID APPROVAL_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ASSET_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private AssetRepository assetRepo;
    private TrinoConnectionFactory trinoConnectionFactory;
    private JdbcTemplate jdbc;
    private Connection connection;
    private Statement statement;
    private CatalogSchemaChangeService service;

    @BeforeEach
    void setUp() throws Exception {
        TenantContext.setTenantId(TENANT_ID);
        TenantContext.setUserId(USER_ID);
        assetRepo = mock(AssetRepository.class);
        trinoConnectionFactory = mock(TrinoConnectionFactory.class);
        jdbc = mock(JdbcTemplate.class);
        connection = mock(Connection.class);
        statement = mock(Statement.class);
        when(trinoConnectionFactory.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(assetRepo.save(any(Asset.class))).thenAnswer(invocation -> invocation.getArgument(0));
        service = new CatalogSchemaChangeService(assetRepo, trinoConnectionFactory, jdbc);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void executeApprovedAddColumnRunsTrinoDdlAndUpdatesCatalog() throws Exception {
        Asset asset = asset();
        when(jdbc.queryForMap(anyString(), eq(TENANT_ID), eq(APPROVAL_ID))).thenReturn(Map.of(
            "request_type", "SCHEMA_CHANGE",
            "target_ref", "dwd.user_order_wide",
            "status", "APPROVED",
            "payload", """
                {"changeType":"ADD_COLUMN","columnName":"customer_level","dataType":"VARCHAR"}
                """
        ));
        when(assetRepo.findByTenantIdAndOmFqn(TENANT_ID, "dwd.user_order_wide")).thenReturn(Optional.of(asset));

        SchemaChangeExecutionResultDTO result = service.executeApproved(APPROVAL_ID);

        verify(statement).execute("ALTER TABLE iceberg.\"dwd\".\"user_order_wide\" ADD COLUMN \"customer_level\" VARCHAR");
        ArgumentCaptor<Asset> assetCaptor = ArgumentCaptor.forClass(Asset.class);
        verify(assetRepo).save(assetCaptor.capture());
        assertThat(assetCaptor.getValue().getColumns()).contains("\"name\":\"customer_level\"", "\"type\":\"VARCHAR\"");
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(
            eq("UPDATE security.approval_request SET payload = CAST(? AS jsonb) WHERE id = ? AND tenant_id = ?"),
            payloadCaptor.capture(),
            eq(APPROVAL_ID),
            eq(TENANT_ID)
        );
        assertThat(payloadCaptor.getValue()).contains("SUCCEEDED", "executionSql");
        assertThat(result.status()).isEqualTo("SUCCEEDED");
    }

    @Test
    void executeRejectsPendingApprovalBeforeConnectingToTrino() throws Exception {
        when(jdbc.queryForMap(anyString(), eq(TENANT_ID), eq(APPROVAL_ID))).thenReturn(Map.of(
            "request_type", "SCHEMA_CHANGE",
            "target_ref", "dwd.user_order_wide",
            "status", "PENDING",
            "payload", "{}"
        ));

        assertThatThrownBy(() -> service.executeApproved(APPROVAL_ID))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("审批未通过");
        verify(trinoConnectionFactory, never()).getConnection();
    }

    @Test
    void executeSkipsAlreadySucceededApproval() throws Exception {
        when(jdbc.queryForMap(anyString(), eq(TENANT_ID), eq(APPROVAL_ID))).thenReturn(Map.of(
            "request_type", "SCHEMA_CHANGE",
            "target_ref", "dwd.user_order_wide",
            "status", "APPROVED",
            "payload", """
                {"changeType":"ADD_COLUMN","executionStatus":"SUCCEEDED","executionSql":"ALTER TABLE x","executedAt":"2026-06-30T00:00:00Z"}
                """
        ));

        SchemaChangeExecutionResultDTO result = service.executeApproved(APPROVAL_ID);

        assertThat(result.message()).contains("无需重复提交");
        verify(trinoConnectionFactory, never()).getConnection();
        verify(assetRepo, never()).save(any());
    }

    private Asset asset() {
        Asset asset = new Asset();
        asset.setId(ASSET_ID);
        asset.setTenantId(TENANT_ID);
        asset.setOmFqn("dwd.user_order_wide");
        asset.setColumns(JsonUtil.toJson(List.of(
            Map.of("name", "order_id", "type", "BIGINT"),
            Map.of("name", "phone", "type", "VARCHAR", "classification", "L3")
        )));
        return asset;
    }
}
