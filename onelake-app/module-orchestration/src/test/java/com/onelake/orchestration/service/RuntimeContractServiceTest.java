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

@ExtendWith(MockitoExtension.class)
class RuntimeContractServiceTest {

    @Mock
    private DagsterClient dagster;

    @Test
    void listRuntimeContractsMarksSparkAndPythonAsContractOnlyUntilJobsExist() {
        RuntimeContractService service = new RuntimeContractService(dagster);
        when(dagster.listJobs("onelake", "onelake-loc"))
            .thenReturn(List.of("onelake_dbt_model_run", "onelake_sync_task_schedule_reconcile"));

        List<RuntimeContractDTO> result = service.listRuntimeContracts();

        assertThat(result).extracting(RuntimeContractDTO::compileTarget)
            .containsExactly("SQL_DBT", "SPARK", "PYTHON");
        assertThat(result).filteredOn(contract -> contract.compileTarget().equals("SQL_DBT"))
            .singleElement()
            .satisfies(contract -> {
                assertThat(contract.status()).isEqualTo("READY");
                assertThat(contract.graphExecutionSupported()).isTrue();
                assertThat(contract.dagsterJobAvailable()).isTrue();
            });
        assertThat(result).filteredOn(contract -> contract.compileTarget().equals("SPARK"))
            .singleElement()
            .satisfies(contract -> {
                assertThat(contract.status()).isEqualTo("MISSING_DAGSTER_JOB");
                assertThat(contract.graphExecutionSupported()).isFalse();
                assertThat(contract.blockedReason()).contains("onelake_spark_operator_run");
            });
    }

    @Test
    void triggerBlockedReasonRejectsSparkDefinition() {
        RuntimeContractService service = new RuntimeContractService(dagster);

        Optional<String> reason = service.triggerBlockedReason("onelake_spark_operator_run",
            Map.of("compileTarget", "SPARK", "engine", "SPARK"));

        assertThat(reason).isPresent();
        assertThat(reason.get()).contains("SPARK 仍处于 Manifest 契约态");
    }
}
