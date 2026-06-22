package com.onelake.catalog.service;

import com.onelake.catalog.config.TrinoConnectionFactory;
import com.onelake.catalog.domain.entity.Asset;
import com.onelake.catalog.dto.AssetMaintenanceAssessmentDTO;
import com.onelake.catalog.dto.AssetMaintenanceRequest;
import com.onelake.catalog.dto.AssetMaintenanceResultDTO;
import com.onelake.catalog.repository.AssetRepository;
import com.onelake.common.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CatalogMaintenanceServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ASSET_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private AssetRepository assetRepo;
    private TrinoConnectionFactory trinoConnectionFactory;
    private Connection connection;
    private Statement statement;
    private ResultSet resultSet;
    private CatalogMaintenanceService service;

    @BeforeEach
    void setUp() throws Exception {
        TenantContext.setTenantId(TENANT_ID);
        assetRepo = mock(AssetRepository.class);
        trinoConnectionFactory = mock(TrinoConnectionFactory.class);
        connection = mock(Connection.class);
        statement = mock(Statement.class);
        resultSet = mock(ResultSet.class);
        when(trinoConnectionFactory.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        service = new CatalogMaintenanceService(assetRepo, trinoConnectionFactory);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void assessDwdDetectsFreshnessAndSmallFileRisks() throws Exception {
        when(assetRepo.findById(ASSET_ID)).thenReturn(Optional.of(asset("dwd.dwd_trade_orders_df")));
        when(statement.executeQuery(anyString())).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getInt("file_count")).thenReturn(12);
        when(resultSet.getInt("small_file_count")).thenReturn(11);
        when(resultSet.getLong("total_bytes")).thenReturn(4096L);

        AssetMaintenanceAssessmentDTO assessment = service.assess(ASSET_ID);

        assertThat(assessment.status()).isEqualTo("CRITICAL");
        assertThat(assessment.freshnessStatus()).isEqualTo("BREACHED");
        assertThat(assessment.fileCount()).isEqualTo(12);
        assertThat(assessment.smallFileCount()).isEqualTo(11);
        assertThat(assessment.risks()).contains("FRESHNESS_SLA_BREACHED", "SMALL_FILE_RISK");
        assertThat(assessment.suggestedOperations()).contains("OPTIMIZE", "EXPIRE_SNAPSHOTS", "REMOVE_ORPHAN_FILES");
    }

    @Test
    void runMaintenanceExecutesOptimizeStatement() throws Exception {
        Asset asset = asset("dwd.dwd_trade_orders_df");
        when(assetRepo.findById(ASSET_ID)).thenReturn(Optional.of(asset));
        when(assetRepo.save(any(Asset.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(statement.execute(anyString())).thenReturn(false);

        AssetMaintenanceResultDTO result = service.runMaintenance(
            ASSET_ID,
            new AssetMaintenanceRequest("OPTIMIZE")
        );

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(statement).execute(sql.capture());
        assertThat(sql.getValue()).isEqualTo("ALTER TABLE iceberg.\"dwd\".\"dwd_trade_orders_df\" EXECUTE optimize");
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.operation()).isEqualTo("OPTIMIZE");
        assertThat(asset.getSyncedAt()).isNotNull();
    }

    private Asset asset(String fqn) {
        Asset asset = new Asset();
        asset.setId(ASSET_ID);
        asset.setTenantId(TENANT_ID);
        asset.setOmFqn(fqn);
        asset.setAssetType("TABLE");
        asset.setLayer("DWD");
        asset.setLastSyncAt(Instant.now().minusSeconds(7200));
        return asset;
    }
}
