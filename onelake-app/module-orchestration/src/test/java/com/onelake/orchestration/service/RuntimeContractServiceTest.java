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
 * 运行契约测试：统一流水线只暴露 Spark 作为可运行引擎。
 */
@ExtendWith(MockitoExtension.class)
class RuntimeContractServiceTest {

    @Mock
    private DagsterClient dagster;

    @Test
    void listRuntimeContractsMarksSparkReady() {
        RuntimeContractService service = new RuntimeContractService(dagster);
        when(dagster.listJobs("onelake", "onelake-loc"))
            .thenReturn(List.of("onelake_pipeline_run", "onelake_pipeline_graph_run"));

        List<RuntimeContractDTO> result = service.listRuntimeContracts();

        assertThat(result).extracting(RuntimeContractDTO::dagsterJob)
            .containsExactly("onelake_pipeline_run", "onelake_pipeline_graph_run");

        assertThat(result).allSatisfy(c -> {
                assertThat(c.status()).isEqualTo("READY");
                assertThat(c.compileTarget()).isEqualTo("SPARK");
                assertThat(c.graphExecutionSupported()).isTrue();
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
    void triggerBlockedReasonRejectsNonSparkOnPipelineJob() {
        RuntimeContractService service = new RuntimeContractService(dagster);

        Optional<String> reason = service.triggerBlockedReason("onelake_pipeline_run",
            Map.of("compileTarget", "LEGACY", "engine", "LEGACY"));

        assertThat(reason).isPresent();
        assertThat(reason.get()).contains("Spark");
    }

    @Test
    void triggerBlockedReasonRejectsNonSparkOnGraphPipelineJob() {
        RuntimeContractService service = new RuntimeContractService(dagster);

        Optional<String> reason = service.triggerBlockedReason("onelake_pipeline_graph_run",
            Map.of("compileTarget", "LEGACY", "engine", "LEGACY"));

        assertThat(reason).isPresent();
        assertThat(reason.get()).contains("Spark");
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
