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
 * Runtime-contract tests: unified pipelines expose Spark as the only runnable engine.
 */
@ExtendWith(MockitoExtension.class)
class RuntimeContractServiceTest {

    @Mock
    private DagsterClient dagster;

    @Test
    void listRuntimeContractsMarksSparkReady() {
        RuntimeContractService service = new RuntimeContractService(dagster);
        when(dagster.listJobs("onelake", "onelake-loc"))
            .thenReturn(List.of("onelake_pipeline_run"));

        List<RuntimeContractDTO> result = service.listRuntimeContracts();

        assertThat(result).extracting(RuntimeContractDTO::compileTarget)
            .containsExactly("SPARK");

        assertThat(result).filteredOn(c -> c.compileTarget().equals("SPARK"))
            .singleElement()
            .satisfies(c -> {
                assertThat(c.status()).isEqualTo("READY");
                assertThat(c.graphExecutionSupported()).isTrue();
                assertThat(c.dagsterJob()).isEqualTo("onelake_pipeline_run");
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
    void triggerBlockedReasonRejectsNonSparkOnPipelineJob() {
        RuntimeContractService service = new RuntimeContractService(dagster);

        Optional<String> reason = service.triggerBlockedReason("onelake_pipeline_run",
            Map.of("compileTarget", "LEGACY", "engine", "LEGACY"));

        assertThat(reason).isPresent();
        assertThat(reason.get()).contains("Spark");
    }
}
