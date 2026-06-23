package com.onelake.orchestration.service;

import com.onelake.common.audit.AuditLogger;
import com.onelake.common.context.TenantContext;
import com.onelake.orchestration.domain.entity.ComputeProfile;
import com.onelake.orchestration.domain.entity.ResourceGroup;
import com.onelake.orchestration.dto.ComputeProfileDTO;
import com.onelake.orchestration.dto.ComputeProfileRequest;
import com.onelake.orchestration.dto.ResourceGroupDTO;
import com.onelake.orchestration.dto.ResourceGroupRequest;
import com.onelake.orchestration.repository.ComputeProfileRepository;
import com.onelake.orchestration.repository.ResourceGroupRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResourceGroupServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID GROUP_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock
    private ResourceGroupRepository resourceGroupRepo;

    @Mock
    private ComputeProfileRepository computeProfileRepo;

    @Mock
    private AuditLogger audit;

    private ResourceGroupService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT_ID);
        service = new ResourceGroupService(resourceGroupRepo, computeProfileRepo, audit);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void listMergesBuiltinAndTenantOverrideProfiles() {
        ResourceGroup builtin = resourceGroup(null, "default", "TRINO_DBT");
        builtin.setId(UUID.fromString("33333333-3333-3333-3333-333333333333"));
        ResourceGroup tenant = resourceGroup(TENANT_ID, "default", "TRINO_DBT");
        tenant.setId(GROUP_ID);
        ComputeProfile builtinProfile = profile(builtin.getId(), "trino-small", "TRINO_DBT");
        ComputeProfile tenantProfile = profile(GROUP_ID, "trino-codex", "TRINO_DBT");
        when(resourceGroupRepo.findByTenantIdIsNullOrderByCodeAsc()).thenReturn(List.of(builtin));
        when(resourceGroupRepo.findByTenantIdOrderByCodeAsc(TENANT_ID)).thenReturn(List.of(tenant));
        when(computeProfileRepo.findByResourceGroupIdInOrderByCodeAsc(List.of(builtin.getId(), GROUP_ID)))
            .thenReturn(List.of(builtinProfile, tenantProfile));

        List<ResourceGroupDTO> result = service.listResourceGroups();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).builtin()).isFalse();
        assertThat(result.get(0).computeProfiles()).extracting(ComputeProfileDTO::code)
            .containsExactly("trino-codex", "trino-small");
    }

    @Test
    void upsertResourceGroupPersistsTenantScopedRegistryEntry() {
        when(resourceGroupRepo.findByTenantIdAndCode(TENANT_ID, "warehouse-codex")).thenReturn(Optional.empty());
        when(resourceGroupRepo.save(any(ResourceGroup.class))).thenAnswer(invocation -> {
            ResourceGroup group = invocation.getArgument(0);
            group.setId(GROUP_ID);
            return group;
        });
        when(computeProfileRepo.findByResourceGroupIdOrderByCodeAsc(GROUP_ID)).thenReturn(List.of());

        ResourceGroupDTO result = service.upsertResourceGroup(new ResourceGroupRequest(
            "warehouse-codex",
            "Warehouse Codex",
            "TRINO_DBT",
            "ACTIVE",
            4,
            32,
            128,
            Map.of("scanBytesLimit", 1024)
        ));

        assertThat(result.code()).isEqualTo("warehouse-codex");
        assertThat(result.builtin()).isFalse();
        assertThat(result.costPolicy()).containsEntry("scanBytesLimit", 1024);
        verify(audit).auditCreate(any(), any(), any());
    }

    @Test
    void supportsDefaultRegistryWhenDatabaseRowsAreAbsent() {
        when(resourceGroupRepo.findByTenantIdAndCode(TENANT_ID, "default")).thenReturn(Optional.empty());
        when(resourceGroupRepo.findByTenantIdIsNullAndCode("default")).thenReturn(Optional.empty());

        assertThat(service.supportsResourceGroup("TRINO_DBT", "default")).isTrue();
    }

    @Test
    void supportsTenantComputeProfile() {
        ResourceGroup group = resourceGroup(TENANT_ID, "warehouse-codex", "TRINO_DBT");
        group.setId(GROUP_ID);
        ComputeProfile profile = profile(GROUP_ID, "trino-codex", "TRINO_DBT");
        when(resourceGroupRepo.findByTenantIdAndCode(TENANT_ID, "warehouse-codex")).thenReturn(Optional.of(group));
        when(resourceGroupRepo.findByTenantIdIsNullAndCode("warehouse-codex")).thenReturn(Optional.empty());
        when(computeProfileRepo.findByResourceGroupIdAndCode(GROUP_ID, "trino-codex")).thenReturn(Optional.of(profile));

        assertThat(service.supportsComputeProfile("warehouse-codex", "trino-codex")).isTrue();
    }

    @Test
    void upsertComputeProfileRequiresTenantResourceGroup() {
        ResourceGroup group = resourceGroup(TENANT_ID, "warehouse-codex", "TRINO_DBT");
        group.setId(GROUP_ID);
        when(resourceGroupRepo.findByTenantIdAndCode(TENANT_ID, "warehouse-codex")).thenReturn(Optional.of(group));
        when(computeProfileRepo.findByResourceGroupIdAndCode(GROUP_ID, "trino-codex")).thenReturn(Optional.empty());
        when(computeProfileRepo.save(any(ComputeProfile.class))).thenAnswer(invocation -> {
            ComputeProfile profile = invocation.getArgument(0);
            profile.setId(UUID.fromString("44444444-4444-4444-4444-444444444444"));
            return profile;
        });

        ComputeProfileDTO result = service.upsertComputeProfile("warehouse-codex", new ComputeProfileRequest(
            "trino-codex",
            "Trino Codex",
            null,
            "ACTIVE",
            8,
            32,
            2048L,
            1800
        ));

        assertThat(result.code()).isEqualTo("trino-codex");
        assertThat(result.engine()).isEqualTo("TRINO_DBT");
        verify(audit).auditCreate(any(), any(), any());
    }

    private ResourceGroup resourceGroup(UUID tenantId, String code, String engine) {
        ResourceGroup group = new ResourceGroup();
        group.setTenantId(tenantId);
        group.setCode(code);
        group.setDisplayName(code);
        group.setEngine(engine);
        group.setStatus("ACTIVE");
        group.setCostPolicy("{}");
        return group;
    }

    private ComputeProfile profile(UUID resourceGroupId, String code, String engine) {
        ComputeProfile profile = new ComputeProfile();
        profile.setId(UUID.nameUUIDFromBytes((resourceGroupId + ":" + code).getBytes()));
        profile.setResourceGroupId(resourceGroupId);
        profile.setCode(code);
        profile.setDisplayName(code);
        profile.setEngine(engine);
        profile.setStatus("ACTIVE");
        return profile;
    }
}
