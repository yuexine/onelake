package com.onelake.orchestration.service;

import com.onelake.common.audit.AuditLogger;
import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.orchestration.config.BuiltInOperatorCatalog;
import com.onelake.orchestration.domain.entity.Operator;
import com.onelake.orchestration.domain.entity.OperatorInstall;
import com.onelake.orchestration.domain.entity.OperatorVersion;
import com.onelake.orchestration.domain.enums.OperatorStatus;
import com.onelake.orchestration.domain.enums.OperatorScope;
import com.onelake.orchestration.dto.OperatorDTO;
import com.onelake.orchestration.dto.OperatorInstallRequest;
import com.onelake.orchestration.dto.OperatorManifestDTO;
import com.onelake.orchestration.dto.OperatorValidationResultDTO;
import com.onelake.orchestration.repository.OperatorInstallRepository;
import com.onelake.orchestration.repository.OperatorRepository;
import com.onelake.orchestration.repository.OperatorVersionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class OperatorServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID = UUID.fromString("d0e42034-2349-474a-ba4e-bc6d2127343d");

    @Mock
    private OperatorRepository operatorRepo;

    @Mock
    private OperatorVersionRepository versionRepo;

    @Mock
    private OperatorInstallRepository installRepo;

    @Mock
    private AuditLogger audit;

    @Mock
    private ResourceGroupService resourceGroupService;

    private OperatorService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT_ID);
        TenantContext.setUserId(USER_ID);
        lenient().when(resourceGroupService.supportsResourceGroup(anyString(), anyString()))
            .thenAnswer(invocation -> ResourceGroupService.defaultSupportsResourceGroup(
                invocation.getArgument(0), invocation.getArgument(1)));
        lenient().when(resourceGroupService.supportsComputeProfile(anyString(), anyString()))
            .thenAnswer(invocation -> ResourceGroupService.defaultSupportsComputeProfile(
                invocation.getArgument(0), invocation.getArgument(1)));
        service = new OperatorService(operatorRepo, versionRepo, installRepo, audit, resourceGroupService);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void seedBuiltInsCreatesSixtySixOperatorsAndVersions() {
        when(operatorRepo.findByOperatorRefAndScopeAndTenantIdIsNull(anyString(), eq(OperatorScope.BUILTIN)))
            .thenReturn(Optional.empty());
        when(operatorRepo.save(any(Operator.class))).thenAnswer(invocation -> {
            Operator operator = invocation.getArgument(0);
            operator.setId(UUID.randomUUID());
            return operator;
        });
        when(versionRepo.findByOperatorIdAndVersion(any(), anyString())).thenReturn(Optional.empty());

        int seeded = service.seedBuiltIns();

        assertThat(seeded).isEqualTo(66);
        assertThat(BuiltInOperatorCatalog.size()).isEqualTo(66);
        verify(operatorRepo, times(66)).save(any(Operator.class));
        verify(versionRepo, times(66)).save(any(OperatorVersion.class));
    }

    @Test
    void seedBuiltInsNeverOverwritesExistingVersionSnapshots() {
        when(operatorRepo.findByOperatorRefAndScopeAndTenantIdIsNull(anyString(), eq(OperatorScope.BUILTIN)))
            .thenReturn(Optional.empty());
        when(operatorRepo.save(any(Operator.class))).thenAnswer(invocation -> {
            Operator operator = invocation.getArgument(0);
            operator.setId(UUID.randomUUID());
            return operator;
        });
        when(versionRepo.findByOperatorIdAndVersion(any(), anyString()))
            .thenReturn(Optional.of(new OperatorVersion()));

        assertThat(service.seedBuiltIns()).isEqualTo(66);

        verify(versionRepo, never()).save(any(OperatorVersion.class));
    }

    @Test
    void correctedBuiltInsUseNewVersionsAndDeclareAppendProjection() {
        Map<String, OperatorManifestDTO> manifests = BuiltInOperatorCatalog.manifests().stream()
            .collect(java.util.stream.Collectors.toMap(
                OperatorManifestDTO::operatorRef, manifest -> manifest));

        assertThat(manifests.get("govern.trim_whitespace").version()).isEqualTo("1.0.1");
        assertThat(manifests.get("govern.drop_null").version()).isEqualTo("1.0.1");
        assertThat(manifests.get("output.incremental_merge").version()).isEqualTo("1.0.1");
        assertThat(manifests.get("transform.concat_columns").template())
            .containsEntry("selectMode", "APPEND");
        assertThat(manifests.get("transform.concat_columns").version()).isEqualTo("1.0.1");
        assertThat(manifests.get("transform.select_columns").template())
            .doesNotContainKey("selectMode");
    }

    @Test
    void validateRejectsQualityGateWithoutViolationPolicy() {
        OperatorManifestDTO invalid = new OperatorManifestDTO(
            "gate.bad",
            "1.0.0",
            "QUALITY_GATE",
            "CUSTOM",
            "坏门禁",
            "missing policy",
            null,
            List.of("质量"),
            List.of(port()),
            Map.of("mode", "ASSERT"),
            paramsSchema(),
            "SPARK",
            Map.of("kind", "QUALITY_ASSERT", "sql", "not_null"),
            Map.of("type", "ONE_TO_ONE"),
            Map.of("effect", "INHERIT"),
            true,
            new LinkedHashMap<>(),
            Map.of("defaultResourceGroup", "spark-default", "engine", "SPARK"),
            List.of()
        );

        OperatorValidationResultDTO result = service.validateOperator(invalid);

        assertThat(result.ok()).isFalse();
        assertThat(result.errors()).contains("QUALITY_GATE 算子必须声明 policy.actionOnViolation");
    }

    @Test
    void validateAllowsSparkManifest() {
        OperatorManifestDTO manifest = sparkManifest("custom.spark_dedupe", true);

        OperatorValidationResultDTO result = service.validateOperator(manifest);

        assertThat(result.ok()).isTrue();
        assertThat(result.errors()).isEmpty();
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void validateRejectsSparkManifestWithoutResourceContract() {
        OperatorManifestDTO manifest = sparkManifest("custom.spark_dedupe", false);

        OperatorValidationResultDTO result = service.validateOperator(manifest);

        assertThat(result.ok()).isFalse();
        assertThat(result.errors()).contains(
            "compileTarget=SPARK 必须声明 resourceHint.defaultResourceGroup 与 resourceHint.engine");
    }

    @Test
    void validateRejectsManifestWithUnsupportedResourceGroup() {
        OperatorManifestDTO manifest = new OperatorManifestDTO(
            "custom.clean_phone",
            "1.0.0",
            "GOVERN",
            "CUSTOM",
            "手机号清洗",
            "清洗手机号格式",
            "AppstoreOutlined",
            List.of("治理"),
            List.of(port()),
            Map.of("mode", "PASSTHROUGH_MODIFY"),
            paramsSchema(),
            "SPARK",
            Map.of("kind", "COLUMN_EXPR", "sql", "regexp_replace({{ column }}, '[^0-9]', '')"),
            Map.of("type", "ONE_TO_ONE"),
            Map.of("effect", "INHERIT"),
            false,
            new LinkedHashMap<>(),
            Map.of("defaultResourceGroup", "warehouse-xl", "engine", "SPARK"),
            List.of()
        );

        OperatorValidationResultDTO result = service.validateOperator(manifest);

        assertThat(result.ok()).isFalse();
        assertThat(result.errors()).contains(
            "resourceGroup 不存在或不支持当前 engine: warehouse-xl/SPARK");
    }

    @Test
    void validateAllowsTenantRegisteredResourceGroup() {
        when(resourceGroupService.supportsResourceGroup("SPARK", "warehouse-codex")).thenReturn(true);
        OperatorManifestDTO manifest = new OperatorManifestDTO(
            "custom.clean_phone",
            "1.0.0",
            "GOVERN",
            "CUSTOM",
            "手机号清洗",
            "清洗手机号格式",
            "AppstoreOutlined",
            List.of("治理"),
            List.of(port()),
            Map.of("mode", "PASSTHROUGH_MODIFY"),
            paramsSchema(),
            "SPARK",
            Map.of("kind", "COLUMN_EXPR", "sql", "regexp_replace({{ column }}, '[^0-9]', '')"),
            Map.of("type", "ONE_TO_ONE"),
            Map.of("effect", "INHERIT"),
            false,
            new LinkedHashMap<>(),
            Map.of("defaultResourceGroup", "warehouse-codex", "engine", "SPARK"),
            List.of()
        );

        OperatorValidationResultDTO result = service.validateOperator(manifest);

        assertThat(result.ok()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void registerCustomOperatorPersistsVersionAndAudits() {
        OperatorManifestDTO manifest = customManifest("custom.clean_phone", "1.0.0");
        when(operatorRepo.findByTenantIdAndOperatorRefAndScopeIn(eq(TENANT_ID), eq("custom.clean_phone"), any()))
            .thenReturn(List.of());
        when(operatorRepo.save(any(Operator.class))).thenAnswer(invocation -> {
            Operator operator = invocation.getArgument(0);
            operator.setId(UUID.fromString("22222222-2222-2222-2222-222222222222"));
            return operator;
        });
        when(versionRepo.findByOperatorIdAndVersion(any(), eq("1.0.0")))
            .thenReturn(Optional.empty(), Optional.of(version(manifest)));
        when(versionRepo.findByOperatorIdOrderByCreatedAtDesc(any())).thenReturn(List.of(version(manifest)));

        OperatorDTO dto = service.registerOperator(manifest);

        assertThat(dto.operatorRef()).isEqualTo("custom.clean_phone");
        assertThat(dto.scope()).isEqualTo("CUSTOM");
        ArgumentCaptor<OperatorVersion> versionCaptor = ArgumentCaptor.forClass(OperatorVersion.class);
        verify(versionRepo).save(versionCaptor.capture());
        assertThat(versionCaptor.getValue().getManifest()).contains("\"operatorRef\":\"custom.clean_phone\"");
        verify(audit).auditCreate(eq("operator"), eq(UUID.fromString("22222222-2222-2222-2222-222222222222")), any());
    }

    @Test
    void installRejectsUnknownPinnedVersion() {
        Operator operator = builtinOperator("mask.partial");
        when(operatorRepo.findByTenantIdAndOperatorRefAndScopeIn(eq(TENANT_ID), eq("mask.partial"), any()))
            .thenReturn(List.of());
        when(operatorRepo.findByOperatorRefAndScopeAndTenantIdIsNull("mask.partial", OperatorScope.BUILTIN))
            .thenReturn(Optional.of(operator));
        when(versionRepo.findByOperatorIdAndVersion(operator.getId(), "9.9.9")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.installOperator("mask.partial", new OperatorInstallRequest("9.9.9")))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("算子版本不存在");
    }

    @Test
    void listOperatorsIncludesBuiltinManifestAndMarksBuiltinInstalled() {
        Operator operator = builtinOperator("mask.partial");
        OperatorManifestDTO manifest = BuiltInOperatorCatalog.manifests().stream()
            .filter(m -> m.operatorRef().equals("mask.partial"))
            .findFirst()
            .orElseThrow();
        when(installRepo.findByTenantId(TENANT_ID)).thenReturn(List.of());
        when(operatorRepo.findByScopeAndTenantIdIsNull(OperatorScope.BUILTIN)).thenReturn(List.of(operator));
        when(operatorRepo.findByTenantIdAndScopeIn(eq(TENANT_ID), any())).thenReturn(List.of());
        when(versionRepo.findByOperatorIdAndVersion(operator.getId(), "1.0.0")).thenReturn(Optional.of(version(manifest)));

        List<OperatorDTO> result = service.listOperators("MASK", null, "partial");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).operatorRef()).isEqualTo("mask.partial");
        assertThat(result.get(0).installed()).isTrue();
        assertThat(result.get(0).manifest().template()).containsEntry("kind", "COLUMN_EXPR");
    }

    @Test
    void listOperatorsKeepsDeprecatedOperatorsVisibleForLifecycleGovernance() {
        Operator operator = builtinOperator("mask.partial");
        operator.setStatus(OperatorStatus.DEPRECATED);
        OperatorManifestDTO manifest = BuiltInOperatorCatalog.manifests().stream()
            .filter(m -> m.operatorRef().equals("mask.partial"))
            .findFirst()
            .orElseThrow();
        when(installRepo.findByTenantId(TENANT_ID)).thenReturn(List.of());
        when(operatorRepo.findByScopeAndTenantIdIsNull(OperatorScope.BUILTIN)).thenReturn(List.of(operator));
        when(operatorRepo.findByTenantIdAndScopeIn(eq(TENANT_ID), any())).thenReturn(List.of());
        when(versionRepo.findByOperatorIdAndVersion(operator.getId(), "1.0.0")).thenReturn(Optional.of(version(manifest)));

        List<OperatorDTO> result = service.listOperators("MASK", null, "partial");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).status()).isEqualTo("DEPRECATED");
        assertThat(result.get(0).manifest().operatorRef()).isEqualTo("mask.partial");
    }

    @Test
    void listInstalledOperatorsReturnsOnlyActivePaletteOperators() {
        Operator builtin = builtinOperator("mask.partial");
        Operator deprecated = builtinOperator("transform.rename_columns", "TRANSFORM");
        deprecated.setStatus(OperatorStatus.DEPRECATED);
        Operator custom = customOperator("custom.clean_phone", "GOVERN");
        Operator installedPrivate = customOperator("private.enrich_customer", "TRANSFORM");
        installedPrivate.setTenantId(UUID.fromString("33333333-3333-3333-3333-333333333333"));
        installedPrivate.setScope(OperatorScope.TENANT_PRIVATE);

        OperatorInstall install = new OperatorInstall();
        install.setTenantId(TENANT_ID);
        install.setOperatorId(installedPrivate.getId());
        install.setPinnedVersion("1.0.0");

        OperatorManifestDTO builtinManifest = BuiltInOperatorCatalog.manifests().stream()
            .filter(manifest -> manifest.operatorRef().equals("mask.partial"))
            .findFirst().orElseThrow();
        OperatorManifestDTO customManifest = customManifest("custom.clean_phone", "1.0.0");
        OperatorManifestDTO privateManifest = new OperatorManifestDTO(
            "private.enrich_customer", "1.0.0", "TRANSFORM", "TENANT_PRIVATE",
            "客户补全", "补全客户字段", "AppstoreOutlined", List.of("转换"), List.of(port()),
            Map.of("mode", "PASSTHROUGH_MODIFY"), paramsSchema(), "SPARK",
            Map.of("kind", "COLUMN_EXPR", "sql", "trim({{ column }})"),
            Map.of("type", "ONE_TO_ONE"), Map.of("effect", "INHERIT"), false,
            new LinkedHashMap<>(), Map.of("defaultResourceGroup", "spark-default", "engine", "SPARK"),
            List.of(Map.of("params", Map.of("column", "customer_name"))));

        when(installRepo.findByTenantId(TENANT_ID)).thenReturn(List.of(install));
        when(operatorRepo.findByScopeAndTenantIdIsNull(OperatorScope.BUILTIN))
            .thenReturn(List.of(builtin, deprecated));
        when(operatorRepo.findByTenantIdAndScopeIn(eq(TENANT_ID), any()))
            .thenReturn(List.of(custom));
        when(operatorRepo.findById(installedPrivate.getId())).thenReturn(Optional.of(installedPrivate));
        when(versionRepo.findByOperatorIdAndVersion(builtin.getId(), builtin.getLatestVersion()))
            .thenReturn(Optional.of(version(builtin.getId(), builtinManifest)));
        when(versionRepo.findByOperatorIdAndVersion(custom.getId(), custom.getLatestVersion()))
            .thenReturn(Optional.of(version(custom.getId(), customManifest)));
        when(versionRepo.findByOperatorIdAndVersion(installedPrivate.getId(), installedPrivate.getLatestVersion()))
            .thenReturn(Optional.of(version(installedPrivate.getId(), privateManifest)));

        List<OperatorDTO> result = service.listInstalledOperators();

        assertThat(result).extracting(OperatorDTO::operatorRef)
            .containsExactly("private.enrich_customer", "custom.clean_phone", "mask.partial");
        assertThat(result).allMatch(OperatorDTO::installed);
        assertThat(result).noneMatch(operator -> operator.operatorRef().equals("transform.rename_columns"));
        assertThat(result.stream()
            .filter(operator -> operator.operatorRef().equals("private.enrich_customer"))
            .findFirst().orElseThrow().pinnedVersion()).isEqualTo("1.0.0");
    }

    @Test
    void listInstalledOperatorsExcludesActiveOperatorsOutsideG1Contract() {
        Operator supported = builtinOperator("mask.partial");
        Operator unsupported = builtinOperator("join.inner", "JOIN");
        OperatorManifestDTO supportedManifest = BuiltInOperatorCatalog.manifests().stream()
            .filter(manifest -> manifest.operatorRef().equals("mask.partial"))
            .findFirst().orElseThrow();
        OperatorManifestDTO unsupportedManifest = BuiltInOperatorCatalog.manifests().stream()
            .filter(manifest -> manifest.operatorRef().equals("join.inner"))
            .findFirst().orElseThrow();

        when(installRepo.findByTenantId(TENANT_ID)).thenReturn(List.of());
        when(operatorRepo.findByScopeAndTenantIdIsNull(OperatorScope.BUILTIN))
            .thenReturn(List.of(supported, unsupported));
        when(operatorRepo.findByTenantIdAndScopeIn(eq(TENANT_ID), any())).thenReturn(List.of());
        when(versionRepo.findByOperatorIdAndVersion(supported.getId(), supported.getLatestVersion()))
            .thenReturn(Optional.of(version(supported.getId(), supportedManifest)));
        when(versionRepo.findByOperatorIdAndVersion(unsupported.getId(), unsupported.getLatestVersion()))
            .thenReturn(Optional.of(version(unsupported.getId(), unsupportedManifest)));

        assertThat(service.listInstalledOperators())
            .extracting(OperatorDTO::operatorRef)
            .containsExactly("mask.partial");
    }

    @Test
    void listInstalledOperatorsUsesSameRefPrecedenceAsManifestResolution() {
        Operator builtin = builtinOperator("transform.select_columns", "TRANSFORM");
        Operator custom = customOperator("transform.select_columns", "TRANSFORM");
        custom.setId(UUID.fromString("44444444-4444-4444-4444-444444444444"));
        OperatorManifestDTO customManifest = new OperatorManifestDTO(
            "transform.select_columns", "1.0.0", "TRANSFORM", "CUSTOM",
            "租户字段选择", "租户覆盖实现", "AppstoreOutlined", List.of("转换"), List.of(port()),
            Map.of("mode", "DERIVE"), paramsSchema(), "SPARK",
            Map.of("kind", "SELECT_EXPR", "sql", "{{ columns | join(', ') }}"),
            Map.of("type", "ONE_TO_ONE"), Map.of("effect", "INHERIT"), false,
            new LinkedHashMap<>(), Map.of("defaultResourceGroup", "spark-default", "engine", "SPARK"),
            List.of(Map.of("params", Map.of("columns", List.of("tenant_id")))));

        when(installRepo.findByTenantId(TENANT_ID)).thenReturn(List.of());
        when(operatorRepo.findByScopeAndTenantIdIsNull(OperatorScope.BUILTIN)).thenReturn(List.of(builtin));
        when(operatorRepo.findByTenantIdAndScopeIn(eq(TENANT_ID), any())).thenReturn(List.of(custom));
        when(operatorRepo.findByTenantIdAndOperatorRefAndScopeIn(
            eq(TENANT_ID), eq("transform.select_columns"), any())).thenReturn(List.of(custom));
        when(versionRepo.findByOperatorIdAndVersion(custom.getId(), "1.0.0"))
            .thenReturn(Optional.of(version(custom.getId(), customManifest)));

        List<OperatorDTO> palette = service.listInstalledOperators();
        OperatorManifestDTO resolved = service.getManifest(TENANT_ID, "transform.select_columns", "1.0.0");

        assertThat(palette).singleElement().satisfies(operator -> {
            assertThat(operator.id()).isEqualTo(custom.getId());
            assertThat(operator.manifest().displayName()).isEqualTo("租户字段选择");
        });
        assertThat(resolved.displayName()).isEqualTo("租户字段选择");
    }

    @Test
    void listInstalledOperatorsReturnsManifestForPinnedVersion() {
        Operator installed = customOperator("private.clean_phone", "GOVERN");
        installed.setId(UUID.fromString("55555555-5555-5555-5555-555555555555"));
        installed.setTenantId(UUID.fromString("33333333-3333-3333-3333-333333333333"));
        installed.setScope(OperatorScope.TENANT_PRIVATE);
        installed.setLatestVersion("2.0.0");
        OperatorInstall install = new OperatorInstall();
        install.setTenantId(TENANT_ID);
        install.setOperatorId(installed.getId());
        install.setPinnedVersion("1.0.0");
        OperatorManifestDTO pinnedManifest = customManifest("private.clean_phone", "1.0.0");

        when(installRepo.findByTenantId(TENANT_ID)).thenReturn(List.of(install));
        when(operatorRepo.findByScopeAndTenantIdIsNull(OperatorScope.BUILTIN)).thenReturn(List.of());
        when(operatorRepo.findByTenantIdAndScopeIn(eq(TENANT_ID), any())).thenReturn(List.of());
        when(operatorRepo.findById(installed.getId())).thenReturn(Optional.of(installed));
        when(versionRepo.findByOperatorIdAndVersion(installed.getId(), "1.0.0"))
            .thenReturn(Optional.of(version(installed.getId(), pinnedManifest)));

        assertThat(service.listInstalledOperators()).singleElement().satisfies(operator -> {
            assertThat(operator.latestVersion()).isEqualTo("2.0.0");
            assertThat(operator.pinnedVersion()).isEqualTo("1.0.0");
            assertThat(operator.manifest().version()).isEqualTo("1.0.0");
        });
    }

    @Test
    void getInstalledManifestRejectsVersionDifferentFromPinnedVersion() {
        Operator builtin = builtinOperator("mask.partial");
        OperatorInstall install = new OperatorInstall();
        install.setTenantId(TENANT_ID);
        install.setOperatorId(builtin.getId());
        install.setPinnedVersion("1.0.0");
        when(operatorRepo.findByTenantIdAndOperatorRefAndScopeIn(
            eq(TENANT_ID), eq("mask.partial"), any())).thenReturn(List.of());
        when(operatorRepo.findByOperatorRefAndScopeAndTenantIdIsNull(
            "mask.partial", OperatorScope.BUILTIN)).thenReturn(Optional.of(builtin));
        when(installRepo.findByTenantIdAndOperatorId(TENANT_ID, builtin.getId()))
            .thenReturn(Optional.of(install));

        assertThatThrownBy(() -> service.getInstalledManifest(TENANT_ID, "mask.partial", "2.0.0"))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("已固定版本").hasMessageContaining("1.0.0");

        verify(versionRepo, never()).findByOperatorIdAndVersion(any(), anyString());
    }

    @Test
    void sameRefTenantCandidatesUseStableIdForPaletteAndManifestResolution() {
        String ref = "custom.clean_phone";
        Operator laterCustom = customOperator(ref, "GOVERN");
        laterCustom.setId(UUID.fromString("44444444-4444-4444-4444-444444444444"));
        Operator earlierPrivate = customOperator(ref, "GOVERN");
        earlierPrivate.setId(UUID.fromString("11111111-1111-1111-1111-111111111112"));
        earlierPrivate.setScope(OperatorScope.TENANT_PRIVATE);
        OperatorManifestDTO manifest = customManifest(ref, "1.0.0");

        when(installRepo.findByTenantId(TENANT_ID)).thenReturn(List.of());
        when(operatorRepo.findByScopeAndTenantIdIsNull(OperatorScope.BUILTIN)).thenReturn(List.of());
        when(operatorRepo.findByTenantIdAndScopeIn(eq(TENANT_ID), any()))
            .thenReturn(List.of(laterCustom, earlierPrivate));
        when(operatorRepo.findByTenantIdAndOperatorRefAndScopeIn(eq(TENANT_ID), eq(ref), any()))
            .thenReturn(List.of(laterCustom, earlierPrivate));
        when(versionRepo.findByOperatorIdAndVersion(earlierPrivate.getId(), "1.0.0"))
            .thenReturn(Optional.of(version(earlierPrivate.getId(), manifest)));

        List<OperatorDTO> palette = service.listInstalledOperators();
        OperatorManifestDTO resolved = service.getManifest(TENANT_ID, ref, "1.0.0");

        assertThat(palette).singleElement().extracting(OperatorDTO::id)
            .isEqualTo(earlierPrivate.getId());
        assertThat(resolved.operatorRef()).isEqualTo(ref);
        verify(versionRepo, never()).findByOperatorIdAndVersion(laterCustom.getId(), "1.0.0");
    }

    @Test
    void validateRejectsNullExampleEntry() {
        OperatorManifestDTO base = customManifest("custom.clean_phone", "1.0.0");
        List<Map<String, Object>> examples = new ArrayList<>();
        examples.add(null);
        OperatorManifestDTO invalid = new OperatorManifestDTO(
            base.operatorRef(), base.version(), base.category(), base.scope(),
            base.displayName(), base.description(), base.icon(), base.tags(),
            base.inputPorts(), base.outputSchema(), base.paramsSchema(), base.compileTarget(),
            base.template(), base.lineageRule(), base.securityRule(), base.qualityEmit(),
            base.policy(), base.resourceHint(), examples);

        OperatorValidationResultDTO result = service.validateOperator(invalid);

        assertThat(result.ok()).isFalse();
        assertThat(result.errors()).contains("examples[0] 不能为空");
    }

    @Test
    void installedCheckTreatsTenantOwnedOperatorAsInstalledAndRejectsDeprecatedOperator() {
        Operator custom = customOperator("custom.clean_phone", "GOVERN");
        Operator deprecated = builtinOperator("mask.partial");
        deprecated.setStatus(OperatorStatus.DEPRECATED);
        when(operatorRepo.findByTenantIdAndOperatorRefAndScopeIn(
            eq(TENANT_ID), eq("custom.clean_phone"), any())).thenReturn(List.of(custom));
        when(operatorRepo.findByTenantIdAndOperatorRefAndScopeIn(
            eq(TENANT_ID), eq("mask.partial"), any())).thenReturn(List.of());
        when(operatorRepo.findByOperatorRefAndScopeAndTenantIdIsNull("mask.partial", OperatorScope.BUILTIN))
            .thenReturn(Optional.of(deprecated));

        assertThat(service.isInstalled(TENANT_ID, "custom.clean_phone")).isTrue();
        assertThat(service.isInstalled(TENANT_ID, "mask.partial")).isFalse();
    }

    @Test
    void installRejectsDeprecatedOperator() {
        Operator operator = builtinOperator("mask.partial");
        operator.setStatus(OperatorStatus.DEPRECATED);
        when(operatorRepo.findByTenantIdAndOperatorRefAndScopeIn(eq(TENANT_ID), eq("mask.partial"), any()))
            .thenReturn(List.of());
        when(operatorRepo.findByOperatorRefAndScopeAndTenantIdIsNull("mask.partial", OperatorScope.BUILTIN))
            .thenReturn(Optional.of(operator));

        assertThatThrownBy(() -> service.installOperator("mask.partial", new OperatorInstallRequest("1.0.0")))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("已废弃算子不能安装或锁定版本");
    }

    @Test
    void validateGraphAcceptsDwdOperatorGraphWithMarketManifests() {
        mockVisibleBuiltInOperator("input.ods_table");
        mockVisibleBuiltInOperator("transform.rename_columns");
        mockVisibleBuiltInOperator("govern.drop_required_missing");
        mockVisibleBuiltInOperator("gate.not_null");
        mockVisibleBuiltInOperator("transform.spark_sql");
        mockVisibleBuiltInOperator("output.iceberg_table");

        OperatorValidationResultDTO result = service.validateGraph(dwdGraph());

        assertThat(result.ok()).isTrue();
        assertThat(result.errors()).isEmpty();
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void validateGraphRejectsMissingRequiredConfigAndCycle() {
        mockVisibleBuiltInOperator("input.ods_table");
        mockVisibleBuiltInOperator("transform.rename_columns");
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("nodes", List.of(
            node("input_ods", "INPUT", "input.ods_table", "1.0.0", Map.of("sourceFqn", "ods.ods_codex_orders")),
            node("transform_mapping", "TRANSFORM", "transform.rename_columns", "1.0.0", Map.of())
        ));
        graph.put("edges", List.of(
            edge("input_ods", "transform_mapping"),
            edge("transform_mapping", "input_ods")
        ));

        OperatorValidationResultDTO result = service.validateGraph(graph);

        assertThat(result.ok()).isFalse();
        assertThat(result.errors()).contains("DAG 存在环路");
        assertThat(result.errors()).anySatisfy(error ->
            assertThat(error).contains("transform_mapping").contains("缺少必需参数").contains("mapping"));
    }

    @Test
    void validateGraphRequiresTargetPortForMultiInputOperator() {
        mockVisibleBuiltInOperator("input.ods_table");
        mockVisibleBuiltInOperator("join.inner");
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("nodes", List.of(
            node("left_input", "INPUT", "input.ods_table", "1.0.0", Map.of("sourceFqn", "ods.left_orders")),
            node("right_input", "INPUT", "input.ods_table", "1.0.0", Map.of("sourceFqn", "ods.right_orders")),
            node("join_orders", "JOIN", "join.inner", "1.0.0",
                Map.of("on", "left.order_id = right.order_id", "select", "*"))
        ));
        graph.put("edges", List.of(
            edge("left_input", "join_orders"),
            edge("right_input", "join_orders")
        ));

        OperatorValidationResultDTO result = service.validateGraph(graph);

        assertThat(result.ok()).isFalse();
        assertThat(result.errors()).contains("节点 join_orders 存在多输入端口，边必须声明 targetPort");
    }

    @Test
    void validateGraphRejectsDuplicateSingleCardinalityTargetPort() {
        mockVisibleBuiltInOperator("input.ods_table");
        mockVisibleBuiltInOperator("join.inner");
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("nodes", List.of(
            node("left_input", "INPUT", "input.ods_table", "1.0.0", Map.of("sourceFqn", "ods.left_orders")),
            node("right_input", "INPUT", "input.ods_table", "1.0.0", Map.of("sourceFqn", "ods.right_orders")),
            node("join_orders", "JOIN", "join.inner", "1.0.0",
                Map.of("on", "left.order_id = right.order_id", "select", "*"))
        ));
        graph.put("edges", List.of(
            edge("left_input", "join_orders", "left"),
            edge("right_input", "join_orders", "left")
        ));

        OperatorValidationResultDTO result = service.validateGraph(graph);

        assertThat(result.ok()).isFalse();
        assertThat(result.errors()).contains("节点 join_orders 输入端口 left 最多允许 1 条输入边");
    }

    @Test
    void validateGraphRejectsUnknownFieldReferenceWhenSchemaIsAvailable() {
        mockVisibleBuiltInOperator("input.ods_table");
        mockVisibleBuiltInOperator("transform.rename_columns");
        mockVisibleBuiltInOperator("gate.not_null");
        mockVisibleBuiltInOperator("output.iceberg_table");
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("sourceColumns", List.of(
            Map.of("name", "order_id", "type", "BIGINT", "classification", "L1"),
            Map.of("name", "amount", "type", "DECIMAL", "classification", "L1")
        ));
        graph.put("outputColumns", List.of("order_id", "amount"));
        graph.put("nodes", List.of(
            node("input_ods", "INPUT", "input.ods_table", "1.0.0",
                Map.of("sourceFqn", "ods.ods_orders")),
            node("transform_mapping", "TRANSFORM", "transform.rename_columns", "1.0.0",
                Map.of("mapping", Map.of("order_id", "order_id", "amount", "amount"))),
            node("quality_gate", "QUALITY_GATE", "gate.not_null", "1.0.0",
                Map.of("columns", List.of("missing_col"), "actionOnViolation", "FAIL")),
            node("output_dwd", "OUTPUT", "output.iceberg_table", "1.0.0",
                Map.of("targetFqn", "dwd.dwd_orders", "columns", List.of("order_id", "amount")))
        ));
        graph.put("edges", List.of(
            edge("input_ods", "transform_mapping"),
            edge("transform_mapping", "quality_gate"),
            edge("quality_gate", "output_dwd")
        ));

        OperatorValidationResultDTO result = service.validateGraph(graph);

        assertThat(result.ok()).isFalse();
        assertThat(result.errors()).contains("节点 quality_gate 引用了不存在的字段: missing_col");
    }

    @Test
    void validateGraphRejectsSensitiveColumnWithoutMaskOrEncrypt() {
        mockVisibleBuiltInOperator("input.ods_table");
        mockVisibleBuiltInOperator("transform.rename_columns");
        mockVisibleBuiltInOperator("output.iceberg_table");
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("sourceColumns", List.of(
            Map.of("name", "user_phone", "type", "VARCHAR", "classification", "L3", "piiType", "PHONE")
        ));
        graph.put("outputColumns", List.of("user_phone"));
        graph.put("nodes", List.of(
            node("input_ods", "INPUT", "input.ods_table", "1.0.0",
                Map.of("sourceFqn", "ods.ods_orders")),
            node("transform_mapping", "TRANSFORM", "transform.rename_columns", "1.0.0",
                Map.of("mapping", Map.of("user_phone", "user_phone"))),
            node("output_dwd", "OUTPUT", "output.iceberg_table", "1.0.0",
                Map.of("targetFqn", "dwd.dwd_orders", "columns", List.of("user_phone")))
        ));
        graph.put("edges", List.of(
            edge("input_ods", "transform_mapping"),
            edge("transform_mapping", "output_dwd")
        ));

        OperatorValidationResultDTO result = service.validateGraph(graph);

        assertThat(result.ok()).isFalse();
        assertThat(result.errors()).contains("敏感字段 user_phone 透传到输出但未经过 MASK/ENCRYPT 算子");
    }

    @Test
    void validateGraphAllowsSensitiveColumnWhenProtectedByMask() {
        mockVisibleBuiltInOperator("input.ods_table");
        mockVisibleBuiltInOperator("mask.partial");
        mockVisibleBuiltInOperator("output.view");
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("sourceColumns", List.of(
            Map.of("name", "user_phone", "type", "VARCHAR", "classification", "L3", "piiType", "PHONE")
        ));
        graph.put("outputColumns", List.of("user_phone"));
        graph.put("nodes", List.of(
            node("input_ods", "INPUT", "input.ods_table", "1.0.0",
                Map.of("sourceFqn", "ods.ods_orders")),
            node("mask_phone", "MASK", "mask.partial", "1.0.0",
                Map.of("column", "user_phone", "keepHead", 3, "keepTail", 4)),
            node("output_dwd", "OUTPUT", "output.view", "1.0.0",
                Map.of("targetFqn", "dwd.dwd_orders", "columns", List.of("user_phone")))
        ));
        graph.put("edges", List.of(
            edge("input_ods", "mask_phone"),
            edge("mask_phone", "output_dwd")
        ));

        OperatorValidationResultDTO result = service.validateGraph(graph);

        assertThat(result.ok()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void validateGraphRejectsUnsupportedResourceGroup() {
        mockVisibleBuiltInOperator("input.ods_table");
        mockVisibleBuiltInOperator("transform.rename_columns");
        mockVisibleBuiltInOperator("govern.drop_required_missing");
        mockVisibleBuiltInOperator("gate.not_null");
        mockVisibleBuiltInOperator("transform.spark_sql");
        mockVisibleBuiltInOperator("output.iceberg_table");
        Map<String, Object> graph = dwdGraph();
        graph.put("engine", "SPARK");
        graph.put("resourceGroup", "warehouse-xl");
        graph.put("computeProfile", "spark-small");

        OperatorValidationResultDTO result = service.validateGraph(graph);

        assertThat(result.ok()).isFalse();
        assertThat(result.errors()).contains(
            "resourceGroup 不存在或不支持当前 engine: warehouse-xl/SPARK");
    }

    @Test
    void validateGraphRejectsUnsupportedComputeProfile() {
        mockVisibleBuiltInOperator("input.ods_table");
        mockVisibleBuiltInOperator("transform.rename_columns");
        mockVisibleBuiltInOperator("govern.drop_required_missing");
        mockVisibleBuiltInOperator("gate.not_null");
        mockVisibleBuiltInOperator("transform.spark_sql");
        mockVisibleBuiltInOperator("output.iceberg_table");
        Map<String, Object> graph = dwdGraph();
        graph.put("engine", "SPARK");
        graph.put("resourceGroup", "spark-default");
        graph.put("computeProfile", "retired-small");

        OperatorValidationResultDTO result = service.validateGraph(graph);

        assertThat(result.ok()).isFalse();
        assertThat(result.errors()).contains(
            "computeProfile 不存在或不属于当前 resourceGroup: retired-small/spark-default");
    }

    @Test
    void validateGraphRejectsDeprecatedOperatorNode() {
        mockVisibleBuiltInOperator("input.ods_table");
        mockVisibleDeprecatedBuiltInOperator("transform.rename_columns");
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("nodes", List.of(
            node("input_ods", "INPUT", "input.ods_table", "1.0.0", Map.of("sourceFqn", "ods.ods_codex_orders")),
            node("transform_mapping", "TRANSFORM", "transform.rename_columns", "1.0.0",
                Map.of("mapping", Map.of("order_id", "order_id")))
        ));
        graph.put("edges", List.of(edge("input_ods", "transform_mapping")));

        OperatorValidationResultDTO result = service.validateGraph(graph);

        assertThat(result.ok()).isFalse();
        assertThat(result.errors()).contains("节点 transform_mapping 引用了已废弃算子: transform.rename_columns");
    }

    @Test
    void validateGraphAllowsSparkManifestNode() {
        OperatorManifestDTO manifest = sparkManifest("custom.spark_dedupe", true);
        Operator operator = customOperator("custom.spark_dedupe", "TRANSFORM");
        when(operatorRepo.findByTenantIdAndOperatorRefAndScopeIn(eq(TENANT_ID), eq("custom.spark_dedupe"), any()))
            .thenReturn(List.of(operator));
        when(versionRepo.findByOperatorIdAndVersion(operator.getId(), "1.0.0"))
            .thenReturn(Optional.of(version(operator.getId(), manifest)));
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("nodes", List.of(
            node("spark_dedupe", "TRANSFORM", "custom.spark_dedupe", "1.0.0", Map.of("keys", List.of("order_id")))
        ));
        graph.put("edges", List.of());

        OperatorValidationResultDTO result = service.validateGraph(graph);

        assertThat(result.ok()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void getManifestResolvesOnlyTheExplicitlyLockedVersion() {
        Operator operator = customOperator("custom.clean_phone", "GOVERN");
        operator.setLatestVersion("2.0.0");
        OperatorManifestDTO lockedManifest = customManifest("custom.clean_phone", "1.2.3");
        when(operatorRepo.findByTenantIdAndOperatorRefAndScopeIn(
                eq(TENANT_ID), eq("custom.clean_phone"), any()))
                .thenReturn(List.of(operator));
        when(versionRepo.findByOperatorIdAndVersion(operator.getId(), "1.2.3"))
                .thenReturn(Optional.of(version(operator.getId(), lockedManifest)));

        OperatorManifestDTO resolved = service.getManifest(
                TENANT_ID, "custom.clean_phone", "1.2.3");

        assertThat(resolved.version()).isEqualTo("1.2.3");
        verify(versionRepo).findByOperatorIdAndVersion(operator.getId(), "1.2.3");
    }

    @Test
    void getManifestRejectsMissingLockedVersionInsteadOfUsingLatest() {
        assertThatThrownBy(() -> service.getManifest(
                TENANT_ID, "custom.clean_phone", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("operatorVersion 不能为空");
    }

    private Operator builtinOperator(String ref) {
        return builtinOperator(ref, "MASK");
    }

    private Operator builtinOperator(String ref, String category) {
        Operator operator = new Operator();
        operator.setId(UUID.nameUUIDFromBytes(ref.getBytes(StandardCharsets.UTF_8)));
        operator.setOperatorRef(ref);
        operator.setCategory(com.onelake.orchestration.domain.enums.OperatorCategory.valueOf(category));
        operator.setScope(OperatorScope.BUILTIN);
        operator.setDisplayName("部分掩码");
        operator.setDescription("desc");
        operator.setLatestVersion("1.0.0");
        return operator;
    }

    private Operator customOperator(String ref, String category) {
        Operator operator = builtinOperator(ref, category);
        operator.setTenantId(TENANT_ID);
        operator.setScope(OperatorScope.CUSTOM);
        return operator;
    }

    private OperatorVersion version(OperatorManifestDTO manifest) {
        return version(UUID.fromString("22222222-2222-2222-2222-222222222222"), manifest);
    }

    private OperatorVersion version(UUID operatorId, OperatorManifestDTO manifest) {
        OperatorVersion version = new OperatorVersion();
        version.setId(UUID.randomUUID());
        version.setOperatorId(operatorId);
        version.setVersion(manifest.version());
        version.setManifest(com.onelake.common.util.JsonUtil.toJson(manifest));
        return version;
    }

    private void mockVisibleBuiltInOperator(String ref) {
        OperatorManifestDTO manifest = BuiltInOperatorCatalog.manifests().stream()
            .filter(item -> item.operatorRef().equals(ref))
            .findFirst()
            .orElseThrow();
        Operator operator = builtinOperator(ref, manifest.category());
        when(operatorRepo.findByTenantIdAndOperatorRefAndScopeIn(eq(TENANT_ID), eq(ref), any()))
            .thenReturn(List.of());
        when(operatorRepo.findByOperatorRefAndScopeAndTenantIdIsNull(ref, OperatorScope.BUILTIN))
            .thenReturn(Optional.of(operator));
        when(versionRepo.findByOperatorIdAndVersion(operator.getId(), "1.0.0"))
            .thenReturn(Optional.of(version(operator.getId(), manifest)));
    }

    private void mockVisibleDeprecatedBuiltInOperator(String ref) {
        OperatorManifestDTO manifest = BuiltInOperatorCatalog.manifests().stream()
            .filter(item -> item.operatorRef().equals(ref))
            .findFirst()
            .orElseThrow();
        Operator operator = builtinOperator(ref, manifest.category());
        operator.setStatus(OperatorStatus.DEPRECATED);
        when(operatorRepo.findByTenantIdAndOperatorRefAndScopeIn(eq(TENANT_ID), eq(ref), any()))
            .thenReturn(List.of());
        when(operatorRepo.findByOperatorRefAndScopeAndTenantIdIsNull(ref, OperatorScope.BUILTIN))
            .thenReturn(Optional.of(operator));
    }

    private Map<String, Object> dwdGraph() {
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("engine", "SPARK");
        graph.put("resourceGroup", "spark-default");
        graph.put("computeProfile", "spark-small");
        graph.put("sourceColumns", List.of(
            Map.of("name", "order_id", "classification", "L1"),
            Map.of("name", "order_time", "classification", "L1")
        ));
        graph.put("outputColumns", List.of("order_id", "order_time"));
        graph.put("nodes", List.of(
            node("input_ods", "INPUT", "input.ods_table", "1.0.0", Map.of("sourceFqn", "ods.ods_codex_orders")),
            node("transform_mapping", "TRANSFORM", "transform.rename_columns", "1.0.0",
                Map.of("mapping", Map.of("order_id", "order_id", "order_time", "order_time"))),
            node("govern_clean", "GOVERN", "govern.drop_required_missing", "1.0.0",
                Map.of("requiredColumns", List.of("order_id"))),
            node("quality_gate", "QUALITY_GATE", "gate.not_null", "1.0.0",
                Map.of("columns", List.of("order_id"))),
            node("spark_sql", "TRANSFORM", "transform.spark_sql", "1.0.0",
                Map.of("sql", "select order_id, order_time from staging_orders")),
            node("output_dwd", "OUTPUT", "output.iceberg_table", "1.0.0",
                Map.of("targetFqn", "dwd.dwd_trade_order_df", "columns", List.of("order_id", "order_time"),
                    "partitionBy", "days(order_time)"))
        ));
        graph.put("edges", List.of(
            edge("input_ods", "transform_mapping"),
            edge("transform_mapping", "govern_clean"),
            edge("govern_clean", "quality_gate"),
            edge("quality_gate", "spark_sql"),
            edge("spark_sql", "output_dwd")
        ));
        return graph;
    }

    private Map<String, Object> node(
        String id,
        String nodeType,
        String operatorRef,
        String operatorVersion,
        Map<String, Object> config
    ) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("nodeType", nodeType);
        node.put("type", nodeType);
        node.put("operatorRef", operatorRef);
        node.put("operatorVersion", operatorVersion);
        node.put("config", config);
        return node;
    }

    private Map<String, String> edge(String source, String target) {
        return Map.of("source", source, "target", target);
    }

    private Map<String, String> edge(String source, String target, String targetPort) {
        return Map.of("source", source, "target", target, "sourcePort", "out", "targetPort", targetPort);
    }

    private OperatorManifestDTO customManifest(String ref, String version) {
        return new OperatorManifestDTO(
            ref,
            version,
            "GOVERN",
            "CUSTOM",
            "手机号清洗",
            "清洗手机号格式",
            "AppstoreOutlined",
            List.of("治理"),
            List.of(port()),
            Map.of("mode", "PASSTHROUGH_MODIFY"),
            paramsSchema(),
            "SPARK",
            Map.of("kind", "COLUMN_EXPR", "sql", "regexp_replace({{ column }}, '[^0-9]', '')"),
            Map.of("type", "ONE_TO_ONE"),
            Map.of("effect", "INHERIT"),
            false,
            new LinkedHashMap<>(),
            Map.of("defaultResourceGroup", "spark-default", "engine", "SPARK"),
            List.of(Map.of("title", "示例", "params", Map.of("column", "phone")))
        );
    }

    private OperatorManifestDTO sparkManifest(String ref, boolean withResourceHint) {
        return new OperatorManifestDTO(
            ref,
            "1.0.0",
            "TRANSFORM",
            "CUSTOM",
            "Spark 去重扩展",
            "声明 Spark 扩展态算子",
            "AppstoreOutlined",
            List.of("spark"),
            List.of(),
            Map.of("mode", "PASSTHROUGH"),
            Map.of("type", "object", "properties", Map.of("keys", Map.of("type", "array"))),
            "SPARK",
            Map.of("kind", "SPARK_SQL", "sql", "select * from {{ input }}"),
            Map.of("type", "ONE_TO_ONE"),
            Map.of("effect", "INHERIT"),
            false,
            new LinkedHashMap<>(),
            withResourceHint ? Map.of("defaultResourceGroup", "spark-default", "engine", "SPARK") : null,
            List.of()
        );
    }

    private Map<String, Object> port() {
        Map<String, Object> port = new LinkedHashMap<>();
        port.put("name", "in");
        port.put("cardinality", "ONE");
        port.put("accept", "TABLE");
        return port;
    }

    private Map<String, Object> paramsSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("required", List.of("column"));
        schema.put("properties", Map.of("column", Map.of("type", "string")));
        return schema;
    }
}
