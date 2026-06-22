package com.onelake.catalog.service;

import com.onelake.catalog.config.TrinoConnectionFactory;
import com.onelake.catalog.domain.entity.Asset;
import com.onelake.catalog.dto.AssetDTO;
import com.onelake.catalog.dto.TableCreateRequest;
import com.onelake.catalog.repository.AssetRepository;
import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CatalogTableServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ASSET_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private AssetRepository assetRepo;
    private CatalogService catalogService;
    private TrinoConnectionFactory trinoConnectionFactory;
    private Connection connection;
    private Statement statement;
    private CatalogTableService service;

    @BeforeEach
    void setUp() throws Exception {
        TenantContext.setTenantId(TENANT_ID);
        TenantContext.setUserId(USER_ID);
        TenantContext.setUsername("dev");
        assetRepo = mock(AssetRepository.class);
        catalogService = mock(CatalogService.class);
        trinoConnectionFactory = mock(TrinoConnectionFactory.class);
        connection = mock(Connection.class);
        statement = mock(Statement.class);
        when(trinoConnectionFactory.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(assetRepo.save(any(Asset.class))).thenAnswer(invocation -> {
            Asset asset = invocation.getArgument(0);
            asset.setId(ASSET_ID);
            return asset;
        });
        when(catalogService.getAsset(ASSET_ID)).thenReturn(assetDto());
        service = new CatalogTableService(assetRepo, catalogService, trinoConnectionFactory);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void createTableExecutesTrinoDdlAndRegistersCatalogAsset() throws Exception {
        when(assetRepo.findByTenantIdAndOmFqn(TENANT_ID, "dwd.dwd_trade_order_df")).thenReturn(Optional.empty());

        AssetDTO created = service.createTable(request());

        verify(statement).execute("CREATE SCHEMA IF NOT EXISTS dwd");
        verify(statement).execute("CREATE TABLE dwd.dwd_trade_order_df (order_id BIGINT, phone VARCHAR, created_at TIMESTAMP) WITH (format = 'PARQUET', partitioning = ARRAY['day(created_at)'])");
        org.mockito.ArgumentCaptor<Asset> captor = org.mockito.ArgumentCaptor.forClass(Asset.class);
        verify(assetRepo).save(captor.capture());
        Asset asset = captor.getValue();
        assertThat(asset.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(asset.getOmFqn()).isEqualTo("dwd.dwd_trade_order_df");
        assertThat(asset.getLayer()).isEqualTo("DWD");
        assertThat(asset.getDomain()).isEqualTo("交易");
        assertThat(asset.getOwnerId()).isEqualTo(USER_ID);
        assertThat(asset.getOwnerName()).isEqualTo("dev");
        assertThat(asset.getColumns()).contains("\"name\":\"phone\"", "\"type\":\"VARCHAR\"", "\"classification\":\"L3\"");
        assertThat(asset.getClassification()).isEqualTo("L3");
        assertThat(asset.getPartitions()).contains("day(created_at)");
        assertThat(asset.getFormat()).isEqualTo("ICEBERG");
        assertThat(asset.getRowCount()).isZero();
        assertThat(created.fqn()).isEqualTo("dwd.dwd_trade_order_df");
    }

    @Test
    void createTableReturnsRealTrinoErrorAndDoesNotRegisterAsset() throws Exception {
        when(assetRepo.findByTenantIdAndOmFqn(TENANT_ID, "dwd.dwd_trade_order_df")).thenReturn(Optional.empty());
        when(statement.execute(org.mockito.ArgumentMatchers.startsWith("CREATE TABLE"))).thenThrow(new SQLException("schema dwd not found"));

        assertThatThrownBy(() -> service.createTable(request()))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("Trino 建表失败: schema dwd not found");
        verify(assetRepo, never()).save(any());
    }

    @Test
    void createTableRejectsInvalidPartitionColumnBeforeConnectingToTrino() throws Exception {
        TableCreateRequest bad = new TableCreateRequest(
            "DWD",
            "交易",
            "dwd_trade_order_df",
            "订单明细",
            request().columns(),
            "days(missing_col)",
            "ICEBERG",
            "ZSTD",
            365,
            90
        );

        assertThatThrownBy(() -> service.createTable(bad))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("分区字段不存在: missing_col");
        verify(trinoConnectionFactory, never()).getConnection();
    }

    private TableCreateRequest request() {
        return new TableCreateRequest(
            "DWD",
            "交易",
            "dwd_trade_order_df",
            "订单明细",
            List.of(
                new TableCreateRequest.ColumnCreateRequest("order_id", "BIGINT", true, null, "订单号"),
                new TableCreateRequest.ColumnCreateRequest("phone", "STRING", false, "L3", "手机号"),
                new TableCreateRequest.ColumnCreateRequest("created_at", "TIMESTAMP", false, null, "创建时间")
            ),
            "days(created_at)",
            "ICEBERG",
            "ZSTD",
            365,
            90
        );
    }

    private AssetDTO assetDto() {
        return new AssetDTO(
            ASSET_ID,
            "dwd.dwd_trade_order_df",
            "dwd_trade_order_df",
            "TABLE",
            "DWD",
            "交易",
            USER_ID,
            "dev",
            "订单明细",
            List.of("交易", "manual"),
            "L3",
            null,
            0,
            0,
            0L,
            null,
            List.of(),
            List.of("day(created_at)"),
            "ICEBERG",
            Instant.now(),
            Instant.now()
        );
    }
}
