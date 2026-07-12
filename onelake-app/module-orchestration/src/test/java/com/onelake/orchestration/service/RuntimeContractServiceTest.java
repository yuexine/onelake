package com.onelake.orchestration.service;

import com.onelake.orchestration.client.DagsterClient;
import com.onelake.orchestration.dto.RuntimeContractDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.onelake.common.context.TenantContext;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 运行契约测试：按 M4 能力矩阵区分 Spark、Trino 与受限脚本引擎。
 */
@ExtendWith(MockitoExtension.class)
class RuntimeContractServiceTest {

    @Mock
    private DagsterClient dagster;

    private RuntimeContractService restrictedService() {
        return new RuntimeContractService(dagster, new ScriptSandboxPolicy(false, ""));
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void listRuntimeContractsReflectsEngineCapabilityMatrix() {
        RuntimeContractService service = restrictedService();
        when(dagster.listJobs("onelake", "onelake-loc"))
            .thenReturn(List.of("onelake_pipeline_run", "onelake_pipeline_graph_run"));

        List<RuntimeContractDTO> result = service.listRuntimeContracts();

        assertThat(result).hasSize(6);
        assertThat(result).filteredOn(c -> "SPARK".equals(c.engine()))
                .allSatisfy(c -> assertThat(c.status()).isEqualTo("READY"));
        assertThat(result).filteredOn(c -> "TRINO".equals(c.engine()))
                .singleElement()
                .satisfies(c -> assertThat(c.status()).isEqualTo("READY"));
        assertThat(result).filteredOn(c -> "CONTROL".equals(c.engine()))
                .singleElement()
                .satisfies(c -> assertThat(c.status()).isEqualTo("READY"));
        assertThat(result).filteredOn(c -> "OBSERVE".equals(c.engine()))
                .singleElement()
                .satisfies(c -> assertThat(c.status()).isEqualTo("READY"));
        assertThat(result).filteredOn(c -> "SCRIPT".equals(c.engine()))
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.status()).isEqualTo("RESTRICTED");
                    assertThat(c.blockedReason()).contains("默认关闭");
                });
    }

    @Test
    void triggerBlockedReasonAllowsSparkWhenPipelineJobIsUsed() {
        RuntimeContractService service = restrictedService();

        Optional<String> reason = service.triggerBlockedReason("onelake_pipeline_run",
            Map.of("compileTarget", "SPARK", "engine", "SPARK"));

        assertThat(reason).isEmpty();
    }

    @Test
    void triggerBlockedReasonAllowsSparkWhenGraphPipelineJobIsUsed() {
        RuntimeContractService service = restrictedService();

        Optional<String> reason = service.triggerBlockedReason("onelake_pipeline_graph_run",
            Map.of("compileTarget", "SPARK", "engine", "SPARK"));

        assertThat(reason).isEmpty();
    }

    @Test
    void triggerBlockedReasonRejectsUnknownEngineOnPipelineJob() {
        RuntimeContractService service = restrictedService();

        Optional<String> reason = service.triggerBlockedReason("onelake_pipeline_run",
            Map.of("compileTarget", "LEGACY", "engine", "LEGACY"));

        assertThat(reason).isPresent();
        assertThat(reason.get()).contains("未在运行契约中注册");
    }

    @Test
    void triggerBlockedReasonAllowsTrinoOnGraphPipelineJob() {
        RuntimeContractService service = restrictedService();

        Optional<String> reason = service.triggerBlockedReason("onelake_pipeline_graph_run",
            Map.of("compileTarget", "TRINO_SQL"));

        assertThat(reason).isEmpty();
    }

    @Test
    void triggerBlockedReasonAllowsBranchOnControlGraphJob() {
        RuntimeContractService service = restrictedService();

        Optional<String> reason = service.triggerBlockedReason(
                "onelake_pipeline_graph_run", Map.of("compileTarget", "BRANCH"));

        assertThat(reason).isEmpty();
    }

    @Test
    void triggerBlockedReasonAllowsSensorAndWaitOnGraphJob() {
        RuntimeContractService service = restrictedService();

        assertThat(service.triggerBlockedReason(
                "onelake_pipeline_graph_run", Map.of("compileTarget", "SENSOR"))).isEmpty();
        assertThat(service.triggerBlockedReason(
                "onelake_pipeline_graph_run", Map.of("compileTarget", "WAIT"))).isEmpty();
    }

    @Test
    void triggerBlockedReasonKeepsScriptEngineRestricted() {
        RuntimeContractService service = restrictedService();

        Optional<String> reason = service.triggerBlockedReason("onelake_pipeline_graph_run",
            Map.of("engine", "PYTHON"));

        assertThat(reason).isPresent();
        assertThat(reason.get()).contains("默认关闭");
    }

    @Test
    void triggerBlockedReasonAllowsScriptWhenSandboxIsExplicitlyEnabled() {
        TenantContext.setTenantId(java.util.UUID.randomUUID());
        RuntimeContractService service = new RuntimeContractService(
                dagster, new ScriptSandboxPolicy(true, "*"));

        Optional<String> reason = service.triggerBlockedReason(
                "onelake_pipeline_graph_run", Map.of("engine", "PYTHON"));

        assertThat(reason).isEmpty();
    }

    @Test
    void triggerBlockedReasonRejectsScriptForTenantOutsideAllowlist() {
        TenantContext.setTenantId(java.util.UUID.randomUUID());
        RuntimeContractService service = new RuntimeContractService(
                dagster,
                new ScriptSandboxPolicy(true, java.util.UUID.randomUUID().toString()));

        Optional<String> reason = service.triggerBlockedReason(
                "onelake_pipeline_graph_run", Map.of("engine", "PYTHON"));

        assertThat(reason).isPresent();
        assertThat(reason.get()).contains("当前租户未获");
    }

    @Test
    void launchBlockedReasonAllowsGraphPipelineJobWhenAvailable() {
        RuntimeContractService service = restrictedService();
        when(dagster.listJobs("onelake", "onelake-loc"))
            .thenReturn(List.of("onelake_pipeline_run", "onelake_pipeline_graph_run"));

        Optional<String> reason = service.launchBlockedReason("onelake_pipeline_graph_run",
            Map.of("compileTarget", "SPARK", "engine", "SPARK"));

        assertThat(reason).isEmpty();
    }

    @Test
    void launchBlockedReasonRejectsGraphPipelineJobWhenMissingFromDagster() {
        RuntimeContractService service = restrictedService();
        when(dagster.listJobs("onelake", "onelake-loc"))
            .thenReturn(List.of("onelake_pipeline_run"));

        Optional<String> reason = service.launchBlockedReason("onelake_pipeline_graph_run",
            Map.of("compileTarget", "SPARK", "engine", "SPARK"));

        assertThat(reason).isPresent();
        assertThat(reason.get()).contains("onelake_pipeline_graph_run");
    }
}
