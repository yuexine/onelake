package com.onelake.catalog.service;

import com.onelake.catalog.config.TrinoConnectionFactory;
import com.onelake.catalog.domain.entity.Asset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CatalogRowCountResolverTest {

    private TrinoConnectionFactory trinoConnectionFactory;
    private CatalogRowCountResolver resolver;

    @BeforeEach
    void setUp() {
        trinoConnectionFactory = mock(TrinoConnectionFactory.class);
        resolver = new CatalogRowCountResolver(trinoConnectionFactory);
        ReflectionTestUtils.setField(resolver, "enabled", true);
        ReflectionTestUtils.setField(resolver, "maxAssets", 50);
        ReflectionTestUtils.setField(resolver, "timeoutSeconds", 10);
    }

    @Test
    void resolvesLiveRowCountFromTrinoCount() throws Exception {
        UUID assetId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        Asset asset = new Asset();
        asset.setId(assetId);
        asset.setAssetType("TABLE");
        asset.setOmFqn("ods.ods_customers_100k");
        asset.setRowCount(10L);

        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(trinoConnectionFactory.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT count(*) FROM ods.ods_customers_100k")).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getLong(1)).thenReturn(2L);

        assertThat(resolver.resolve(List.of(asset))).containsEntry(assetId, 2L);
    }
}
