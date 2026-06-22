package com.onelake.security.service;

import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.security.domain.entity.AclResource;
import com.onelake.security.repository.AclResourceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AclServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OTHER_USER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID RESOURCE_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private AclResourceRepository repo;
    private AclService service;

    @BeforeEach
    void setUp() {
        repo = mock(AclResourceRepository.class);
        service = new AclService(repo);
        TenantContext.setTenantId(TENANT_ID);
        TenantContext.setUserId(OTHER_USER_ID);
        TenantContext.setUsername("consumer");
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
            "consumer", "n/a", "ROLE_DE"
        ));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void ownerAlwaysHasViewAndEditAccess() {
        TenantContext.setUserId(OWNER_ID);
        assertThat(service.canView(AclService.RESOURCE_SAVED_QUERY, RESOURCE_ID, OWNER_ID)).isTrue();
        assertThat(service.canEdit(AclService.RESOURCE_SAVED_QUERY, RESOURCE_ID, OWNER_ID)).isTrue();
        verify(repo, never()).findVisibleResourceIds(any(), any(), any(), any(), any());
    }

    @Test
    void nonOwnerWithoutGrantCannotView() {
        when(repo.findVisibleResourceIds(any(), any(), any(), any(), any())).thenReturn(List.of());
        assertThat(service.canView(AclService.RESOURCE_SAVED_QUERY, RESOURCE_ID, OWNER_ID)).isFalse();
    }

    @Test
    void nonOwnerWithRoleDeGrantCanView() {
        when(repo.findVisibleResourceIds(
            eq(TENANT_ID), eq(AclService.RESOURCE_SAVED_QUERY), eq("VIEW"), eq(OTHER_USER_ID), any()
        )).thenReturn(List.of(RESOURCE_ID));

        assertThat(service.canView(AclService.RESOURCE_SAVED_QUERY, RESOURCE_ID, OWNER_ID)).isTrue();
    }

    @Test
    void requireEditThrows40302WhenNotOwnerAndNoGrant() {
        when(repo.findVisibleResourceIds(any(), any(), eq("EDIT"), any(), any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.requireEdit(AclService.RESOURCE_SAVED_QUERY, RESOURCE_ID, OWNER_ID))
            .isInstanceOf(BizException.class)
            .satisfies(err -> {
                assertThat(((BizException) err).getCode()).isEqualTo(40302);
            });
    }

    @Test
    void autoGrantOnSharedInsertsRoleDeView() {
        when(repo.existsByTenantIdAndResourceTypeAndResourceIdAndGranteeTypeAndGranteeIdAndPermission(
            any(), any(), any(), any(), any(), any()
        )).thenReturn(false);

        service.autoGrantOnShared(AclService.RESOURCE_QUERY_TEMPLATE, RESOURCE_ID);

        org.mockito.ArgumentCaptor<AclResource> captor = org.mockito.ArgumentCaptor.forClass(AclResource.class);
        verify(repo).save(captor.capture());
        AclResource saved = captor.getValue();
        assertThat(saved.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(saved.getResourceType()).isEqualTo(AclService.RESOURCE_QUERY_TEMPLATE);
        assertThat(saved.getResourceId()).isEqualTo(RESOURCE_ID);
        assertThat(saved.getGranteeType()).isEqualTo("ROLE");
        assertThat(saved.getGranteeId()).isEqualTo(AclService.ROLE_DE_UUID);
        assertThat(saved.getPermission()).isEqualTo("VIEW");
    }

    @Test
    void autoGrantOnSharedIsIdempotent() {
        when(repo.existsByTenantIdAndResourceTypeAndResourceIdAndGranteeTypeAndGranteeIdAndPermission(
            any(), any(), any(), any(), any(), any()
        )).thenReturn(true);

        service.autoGrantOnShared(AclService.RESOURCE_SAVED_QUERY, RESOURCE_ID);

        verify(repo, never()).save(any());
    }

    @Test
    void autoRevokeOnPrivateDeletesAllAcls() {
        when(repo.deleteByResource(TENANT_ID, AclService.RESOURCE_SAVED_QUERY, RESOURCE_ID)).thenReturn(3);

        service.autoRevokeOnPrivate(AclService.RESOURCE_SAVED_QUERY, RESOURCE_ID);

        verify(repo).deleteByResource(TENANT_ID, AclService.RESOURCE_SAVED_QUERY, RESOURCE_ID);
    }

    @Test
    void cleanupOnDeleteRemovesAllAcls() {
        service.cleanupOnDelete(AclService.RESOURCE_QUERY_TEMPLATE, RESOURCE_ID);
        verify(repo).deleteByResource(TENANT_ID, AclService.RESOURCE_QUERY_TEMPLATE, RESOURCE_ID);
    }

    @Test
    void filterViewableKeepsOwnerAndAclGranted() {
        TenantContext.setUserId(OTHER_USER_ID);
        when(repo.findVisibleResourceIds(
            eq(TENANT_ID), eq(AclService.RESOURCE_SAVED_QUERY), eq("VIEW"), eq(OTHER_USER_ID), any()
        )).thenReturn(List.of(UUID.randomUUID()));

        class FakeResource {
            final UUID id;
            final UUID ownerId;
            FakeResource(UUID id, UUID ownerId) { this.id = id; this.ownerId = ownerId; }
        }
        FakeResource mine = new FakeResource(UUID.randomUUID(), OTHER_USER_ID);
        FakeResource shared = new FakeResource(UUID.randomUUID(), OWNER_ID);
        // 让 mock 返回 shared.id 作为 ACL 授权的资源
        when(repo.findVisibleResourceIds(
            eq(TENANT_ID), eq(AclService.RESOURCE_SAVED_QUERY), eq("VIEW"), eq(OTHER_USER_ID), any()
        )).thenReturn(List.of(shared.id));
        FakeResource inaccessible = new FakeResource(UUID.randomUUID(), OWNER_ID);

        List<FakeResource> all = List.of(mine, shared, inaccessible);
        List<FakeResource> viewable = service.filterViewable(all, AclService.RESOURCE_SAVED_QUERY,
            new AclService.ResourceAccessor<FakeResource>() {
                @Override public UUID ownerId(FakeResource r) { return r.ownerId; }
                @Override public UUID resourceId(FakeResource r) { return r.id; }
            });

        assertThat(viewable).hasSize(2);
        assertThat(viewable).extracting(r -> r.id).containsExactlyInAnyOrder(mine.id, shared.id);
    }
}
