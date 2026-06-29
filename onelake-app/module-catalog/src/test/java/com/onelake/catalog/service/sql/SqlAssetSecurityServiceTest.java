package com.onelake.catalog.service.sql;

import com.onelake.catalog.domain.entity.Asset;
import com.onelake.catalog.repository.AssetRepository;
import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.security.service.SecurityService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SqlAssetSecurityServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private AssetRepository assetRepo;
    private SecurityService securityService;
    private SqlAssetSecurityService service;

    @BeforeEach
    void setUp() {
        assetRepo = mock(AssetRepository.class);
        securityService = mock(SecurityService.class);
        service = new SqlAssetSecurityService(assetRepo, securityService);
        TenantContext.setTenantId(TENANT_ID);
        TenantContext.setUserId(USER_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void buildsFieldProtectionsFromCatalogColumns() {
        Asset asset = asset("ods.orders", null);
        asset.setColumns("""
            [
              {"name":"phone","type":"varchar","classification":"L3","piiType":"手机号"},
              {"name":"amount","type":"decimal"}
            ]
            """);
        when(assetRepo.findByTenantIdAndOmFqn(TENANT_ID, "ods.orders")).thenReturn(Optional.of(asset));

        var context = service.validateAndPlan("select phone, amount from ods.orders", 40341, "missing ");

        assertThat(context.protectionsByColumn()).containsKey("phone");
        assertThat(context.protectionsByColumn().get("phone").targetFqn()).isEqualTo("ods.orders.phone");
        assertThat(context.protectionsByColumn()).containsKey("amount");
    }

    @Test
    void mapsSimpleColumnAliasToSourceProtection() {
        Asset asset = asset("ods.orders", null);
        asset.setColumns("""
            [
              {"name":"phone","type":"varchar","classification":"L3","piiType":"手机号"}
            ]
            """);
        when(assetRepo.findByTenantIdAndOmFqn(TENANT_ID, "ods.orders")).thenReturn(Optional.of(asset));

        var context = service.validateAndPlan("select phone as p from ods.orders", 40341, "missing ");

        assertThat(context.protectionsByColumn()).containsKeys("phone", "p");
        assertThat(context.protectionsByColumn().get("p").targetFqn()).isEqualTo("ods.orders.phone");
    }

    @Test
    void mergesSameNamedColumnsConservatively() {
        Asset orders = asset("ods.orders", USER_ID);
        orders.setColumns("""
            [
              {"name":"phone","type":"varchar"},
              {"name":"amount","type":"decimal"}
            ]
            """);
        Asset customers = asset("ods.customers", USER_ID);
        customers.setColumns("""
            [
              {"name":"phone","type":"varchar","classification":"L3","piiType":"手机号"}
            ]
            """);
        when(assetRepo.findByTenantIdAndOmFqn(TENANT_ID, "ods.orders")).thenReturn(Optional.of(orders));
        when(assetRepo.findByTenantIdAndOmFqn(TENANT_ID, "ods.customers")).thenReturn(Optional.of(customers));

        var context = service.validateAndPlan(
            "select o.phone, c.phone from ods.orders o join ods.customers c on o.id = c.id",
            40341,
            "missing "
        );

        var protection = context.protectionsByColumn().get("phone");
        assertThat(protection).isNotNull();
        assertThat(protection.classification()).isEqualTo("L3");
        assertThat(protection.targetFqns()).containsExactlyInAnyOrder("ods.orders.phone", "ods.customers.phone");
    }

    @Test
    void rejectsUnregisteredAsset() {
        when(assetRepo.findByTenantIdAndOmFqn(TENANT_ID, "ods.orders")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.validateAndPlan("select * from ods.orders", 40341, "missing "))
            .isInstanceOf(BizException.class)
            .hasMessage("missing ods.orders");
    }

    @Test
    void resolvesKnownCatalogPrefixToRegisteredAssetFqn() {
        Asset asset = asset("dwd.user_governed", USER_ID);
        asset.setColumns("""
            [
              {"name":"mobile","type":"varchar","classification":"L3","piiType":"手机号"}
            ]
            """);
        when(assetRepo.findByTenantIdAndOmFqn(TENANT_ID, "iceberg.dwd.user_governed"))
            .thenReturn(Optional.empty());
        when(assetRepo.findByTenantIdAndOmFqn(TENANT_ID, "dwd.user_governed"))
            .thenReturn(Optional.of(asset));

        var context = service.validateAndPlan(
            "select mobile from iceberg.dwd.user_governed",
            40341,
            "missing "
        );

        assertThat(context.referencedTables()).containsExactly("dwd.user_governed");
        assertThat(context.protectionsByColumn()).containsKey("mobile");
    }

    @Test
    void requiresGrantForNonOwner() {
        when(assetRepo.findByTenantIdAndOmFqn(TENANT_ID, "ods.orders"))
            .thenReturn(Optional.of(asset("ods.orders", null)));
        doThrow(new BizException(40340, "无权查询资产: ods.orders"))
            .when(securityService).requireQueryAccess(anyCollection());

        assertThatThrownBy(() -> service.validateAndPlan("select * from ods.orders", 40341, "missing "))
            .isInstanceOf(BizException.class)
            .hasMessage("无权查询资产: ods.orders");
    }

    @Test
    void skipsGrantForOwner() {
        when(assetRepo.findByTenantIdAndOmFqn(TENANT_ID, "ods.orders"))
            .thenReturn(Optional.of(asset("ods.orders", USER_ID)));

        service.validateAndPlan("select * from ods.orders", 40341, "missing ");

        verify(securityService).requireQueryAccess(java.util.Set.of());
    }

    private Asset asset(String fqn, UUID ownerId) {
        Asset asset = new Asset();
        asset.setTenantId(TENANT_ID);
        asset.setOmFqn(fqn);
        asset.setOwnerId(ownerId);
        asset.setAssetType("TABLE");
        return asset;
    }
}
