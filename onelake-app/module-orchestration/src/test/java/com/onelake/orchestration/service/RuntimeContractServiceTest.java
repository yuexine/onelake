package com.onelake.orchestration.service;

import com.onelake.orchestration.client.DagsterClient;
import com.onelake.orchestration.dto.RuntimeContractDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

    @Test
    void listRuntimeContractsReflectsEngineCapabilityMatrix() {
        RuntimeContractService service = new RuntimeContractService(dagster);
        when(dagster.listJobs("onelake", "onelake-loc"))
            .thenReturn(List.of("onelake_pipeline_run", "onelake_pipeline_graph_run"));

        List<RuntimeContractDTO> result = service.listRuntimeContracts();

        assertThat(result).hasSize(4);
        assertThat(result).filteredOn(c -> "SPARK".equals(c.engine()))
                .allSatisfy(c -> assertThat(c.status()).isEqualTo("READY"));
        assertThat(result).filteredOn(c -> "TRINO".equals(c.engine()))
                .singleElement()
                .satisfies(c -> assertThat(c.status()).isEqualTo("READY"));
        assertThat(result).filteredOn(c -> "SCRIPT".equals(c.engine()))
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.status()).isEqualTo("RESTRICTED");
                    assertThat(c.blockedReason()).contains("隔离沙箱");
                });
    }

    @Test
    void triggerBlockedReasonAllowsSparkWhenPipelineJobIsUsed() {
        RuntimeContractService service = new RuntimeContractService(dagster);

        Optional<String> reason = service.triggerBlockedReason("onelake_pipeline_run",
            Map.of("compileTarget", "SPARK", "engine", "SPARK"));

        assertThat(reason).isEmpty();
    }

    @Test
    void triggerBlockedReasonAllowsSparkWhenGraphPipelineJobIsUsed() {
        RuntimeContractService service = new RuntimeContractService(dagster);

        Optional<String> reason = service.triggerBlockedReason("onelake_pipeline_graph_run",
            Map.of("compileTarget", "SPARK", "engine", "SPARK"));

        assertThat(reason).isEmpty();
    }

    @Test
    void triggerBlockedReasonRejectsUnknownEngineOnPipelineJob() {
        RuntimeContractService service = new RuntimeContractService(dagster);

        Optional<String> reason = service.triggerBlockedReason("onelake_pipeline_run",
            Map.of("compileTarget", "LEGACY", "engine", "LEGACY"));

        assertThat(reason).isPresent();
        assertThat(reason.get()).contains("未在运行契约中注册");
    }

    @Test
    void triggerBlockedReasonAllowsTrinoOnGraphPipelineJob() {
        RuntimeContractService service = new RuntimeContractService(dagster);

        Optional<String> reason = service.triggerBlockedReason("onelake_pipeline_graph_run",
            Map.of("compileTarget", "TRINO_SQL"));

        assertThat(reason).isEmpty();
    }

    @Test
    void triggerBlockedReasonKeepsScriptEngineRestricted() {
        RuntimeContractService service = new RuntimeContractService(dagster);

        Optional<String> reason = service.triggerBlockedReason("onelake_pipeline_graph_run",
            Map.of("engine", "PYTHON"));

        assertThat(reason).isPresent();
        assertThat(reason.get()).contains("受限").contains("隔离沙箱");
    }

    @Test
    void launchBlockedReasonAllowsGraphPipelineJobWhenAvailable() {
        RuntimeContractService service = new RuntimeContractService(dagster);
        when(dagster.listJobs("onelake", "onelake-loc"))
            .thenReturn(List.of("onelake_pipeline_run", "onelake_pipeline_graph_run"));

        Optional<String> reason = service.launchBlockedReason("onelake_pipeline_graph_run",
            Map.of("compileTarget", "SPARK", "engine", "SPARK"));

        assertThat(reason).isEmpty();
    }

    @Test
    void launchBlockedReasonRejectsGraphPipelineJobWhenMissingFromDagster() {
        RuntimeContractService service = new RuntimeContractService(dagster);
        when(dagster.listJobs("onelake", "onelake-loc"))
            .thenReturn(List.of("onelake_pipeline_run"));

        Optional<String> reason = service.launchBlockedReason("onelake_pipeline_graph_run",
            Map.of("compileTarget", "SPARK", "engine", "SPARK"));

        assertThat(reason).isPresent();
        assertThat(reason.get()).contains("onelake_pipeline_graph_run");
    }
}
