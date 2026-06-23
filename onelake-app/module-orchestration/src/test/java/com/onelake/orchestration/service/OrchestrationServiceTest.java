package com.onelake.orchestration.service;

import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.modeling.DwdModelRunSynchronizer;
import com.onelake.orchestration.client.DagsterClient;
import com.onelake.orchestration.domain.entity.Dag;
import com.onelake.orchestration.domain.entity.JobRun;
import com.onelake.orchestration.domain.enums.DagStatus;
import com.onelake.orchestration.domain.enums.TriggerType;
import com.onelake.orchestration.dto.DagDTO;
import com.onelake.orchestration.dto.JobRunDTO;
import com.onelake.orchestration.repository.DagRepository;
import com.onelake.orchestration.repository.JobRunRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class OrchestrationServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DAG_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID RUN_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID MODEL_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Mock
    private DagRepository dagRepo;

    @Mock
    private JobRunRepository runRepo;

    @Mock
    private DagsterClient dagster;

    @Mock
    private JdbcTemplate jdbc;

    @Mock
    private ObjectProvider<DwdModelRunSynchronizer> dwdModelRunSynchronizer;

    @Mock
    private DwdModelRunSynchronizer dwdSynchronizer;

    @Mock
    private RuntimeContractService runtimeContractService;

    private OrchestrationService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT_ID);
        lenient().when(runtimeContractService.triggerBlockedReason(anyString(), anyMap())).thenReturn(Optional.empty());
        service = new OrchestrationService(dagRepo, runRepo, dagster, jdbc, dwdModelRunSynchronizer,
            runtimeContractService);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void updateDagPersistsDefinitionAndIncrementsVersion() {
        Dag dag = dag();
        when(dagRepo.findByIdAndTenantId(DAG_ID, TENANT_ID)).thenReturn(Optional.of(dag));
        Map<String, Object> definition = Map.of(
            "operatorGraph", Map.of(
                "nodes", List.of(Map.of("id", "input_ods", "operatorRef", "input.ods_table")),
                "edges", List.of()
            )
        );

        DagDTO dto = service.updateDag(DAG_ID, "trade_dwd_pipeline", "onelake_dbt_model_run",
            definition, "0 2 * * *", false);

        assertThat(dto.name()).isEqualTo("trade_dwd_pipeline");
        assertThat(dto.version()).isEqualTo(3);
        assertThat(dto.enabled()).isFalse();
        assertThat(dto.definition()).containsKey("operatorGraph");
        assertThat(dag.getDefinition()).contains("input.ods_table");
        verify(dagRepo).save(dag);
    }

    @Test
    void updateDagRejectsDagOutsideTenant() {
        when(dagRepo.findByIdAndTenantId(DAG_ID, TENANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateDag(DAG_ID, "pipeline", "job", Map.of("nodes", List.of()), null, null))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("DAG 不存在");
    }

    @Test
    void listRunsScopesToTenantDagsAndIncludesDagMetadata() {
        Dag dag = dag();
        JobRun run = jobRun(dag.getId());
        PageRequest pageable = PageRequest.of(0, 20);
        when(dagRepo.findByTenantId(TENANT_ID)).thenReturn(List.of(dag));
        when(runRepo.findByDagIdInOrderByStartedAtDesc(
            argThat(ids -> ids != null && ids.size() == 1 && ids.contains(DAG_ID)),
            eq(pageable)
        )).thenReturn(new PageImpl<>(List.of(run), pageable, 1));

        Page<JobRunDTO> page = service.listRuns(pageable);

        assertThat(page.getTotalElements()).isEqualTo(1);
        JobRunDTO dto = page.getContent().get(0);
        assertThat(dto.dagId()).isEqualTo(DAG_ID);
        assertThat(dto.dagName()).isEqualTo("old_pipeline");
        assertThat(dto.dagsterJob()).isEqualTo("old_job");
        assertThat(dto.dagsterRunId()).isEqualTo("dagster-run-1");
        assertThat(dto.status()).isEqualTo("SUCCESS");
        assertThat(dto.triggerType()).isEqualTo("MANUAL");
    }

    @Test
    void listRunsRefreshesNonTerminalDagsterStatus() {
        Dag dag = dag();
        JobRun run = jobRun(dag.getId());
        run.setStatus(DagStatus.RUNNING);
        run.setFinishedAt(null);
        Instant startedAt = Instant.parse("2026-06-23T02:00:00Z");
        Instant finishedAt = Instant.parse("2026-06-23T02:03:00Z");
        PageRequest pageable = PageRequest.of(0, 20);
        when(dagRepo.findByTenantId(TENANT_ID)).thenReturn(List.of(dag));
        when(runRepo.findByDagIdInOrderByStartedAtDesc(
            argThat(ids -> ids != null && ids.contains(DAG_ID)),
            eq(pageable)
        )).thenReturn(new PageImpl<>(List.of(run), pageable, 1));
        when(dagster.getRunStatus("dagster-run-1"))
            .thenReturn(new DagsterClient.RunStatus("dagster-run-1", "SUCCESS", startedAt, finishedAt));

        Page<JobRunDTO> page = service.listRuns(pageable);

        JobRunDTO dto = page.getContent().get(0);
        assertThat(dto.status()).isEqualTo("SUCCESS");
        assertThat(dto.startedAt()).isEqualTo(startedAt);
        assertThat(dto.finishedAt()).isEqualTo(finishedAt);
        verify(runRepo).save(run);
        verify(jdbc).update(startsWith("UPDATE modeling.model_run"),
            eq("SUCCEEDED"), eq(Timestamp.from(startedAt)), eq(Timestamp.from(finishedAt)), eq("dagster-run-1"));
    }

    @Test
    void listRunsSyncsTerminalRunToDwdModelRunWithoutDagsterRefresh() {
        Dag dag = dag();
        JobRun run = jobRun(dag.getId());
        run.setStatus(DagStatus.SUCCESS);
        Instant startedAt = Instant.parse("2026-06-23T02:00:00Z");
        Instant finishedAt = Instant.parse("2026-06-23T02:03:00Z");
        run.setStartedAt(startedAt);
        run.setFinishedAt(finishedAt);
        PageRequest pageable = PageRequest.of(0, 20);
        when(dagRepo.findByTenantId(TENANT_ID)).thenReturn(List.of(dag));
        when(runRepo.findByDagIdInOrderByStartedAtDesc(
            argThat(ids -> ids != null && ids.contains(DAG_ID)),
            eq(pageable)
        )).thenReturn(new PageImpl<>(List.of(run), pageable, 1));

        Page<JobRunDTO> page = service.listRuns(pageable);

        assertThat(page.getContent().get(0).status()).isEqualTo("SUCCESS");
        verify(dagster, never()).getRunStatus("dagster-run-1");
        verify(jdbc).update(startsWith("UPDATE modeling.model_run"),
            eq("SUCCEEDED"), eq(Timestamp.from(startedAt)), eq(Timestamp.from(finishedAt)), eq("dagster-run-1"));
    }

    @Test
    void listRunsDelegatesDwdStatusSyncToModelingSynchronizerWhenAvailable() {
        Dag dag = dag();
        JobRun run = jobRun(dag.getId());
        run.setStatus(DagStatus.SUCCESS);
        run.setFinishedAt(Instant.parse("2026-06-23T02:03:00Z"));
        PageRequest pageable = PageRequest.of(0, 20);
        when(dwdModelRunSynchronizer.getIfAvailable()).thenReturn(dwdSynchronizer);
        when(dwdSynchronizer.refreshByDagsterRunId("dagster-run-1")).thenReturn(true);
        when(dagRepo.findByTenantId(TENANT_ID)).thenReturn(List.of(dag));
        when(runRepo.findByDagIdInOrderByStartedAtDesc(
            argThat(ids -> ids != null && ids.contains(DAG_ID)),
            eq(pageable)
        )).thenReturn(new PageImpl<>(List.of(run), pageable, 1));

        service.listRuns(pageable);

        verify(dwdSynchronizer).refreshByDagsterRunId("dagster-run-1");
        verify(jdbc, never()).update(startsWith("UPDATE modeling.model_run"), any(), any(), any(), any());
    }

    @Test
    void listRunsReturnsEmptyPageWhenTenantHasNoDags() {
        PageRequest pageable = PageRequest.of(0, 20);
        when(dagRepo.findByTenantId(TENANT_ID)).thenReturn(List.of());

        Page<JobRunDTO> page = service.listRuns(pageable);

        assertThat(page.getContent()).isEmpty();
        verifyNoInteractions(runRepo);
    }

    @Test
    void listDagsIncludesLatestRunMetadata() {
        Dag dag = dag();
        when(dagRepo.findByTenantId(TENANT_ID)).thenReturn(List.of(dag));
        when(runRepo.findFirstByDagIdOrderByStartedAtDesc(DAG_ID)).thenReturn(Optional.of(jobRun(DAG_ID)));

        List<DagDTO> dags = service.listDags();

        assertThat(dags).hasSize(1);
        assertThat(dags.get(0).lastRun()).isNotNull();
        assertThat(dags.get(0).lastRun().dagsterRunId()).isEqualTo("dagster-run-1");
        assertThat(dags.get(0).lastRun().status()).isEqualTo("SUCCESS");
        assertThat(dags.get(0).lastRun().dagName()).isEqualTo("old_pipeline");
        assertThat(dags.get(0).triggerable()).isTrue();
        assertThat(dags.get(0).triggerBlockedReason()).isNull();
    }

    @Test
    void triggerDagPersistsQueuedThenRunningRun() {
        Dag dag = dag();
        List<DagStatus> statuses = captureRunStatuses();
        when(dagRepo.findByIdAndTenantId(DAG_ID, TENANT_ID)).thenReturn(Optional.of(dag));
        when(dagster.launch("old_job", "onelake", "onelake-loc")).thenReturn("dagster-run-ok");

        UUID runId = service.triggerDag(DAG_ID, TriggerType.MANUAL);

        assertThat(runId).isEqualTo(RUN_ID);
        assertThat(statuses).containsExactly(DagStatus.QUEUED, DagStatus.RUNNING);
        verify(runRepo, times(2)).save(any(JobRun.class));
    }

    @Test
    void triggerDagKeepsFailedRunWhenDagsterLaunchFails() {
        Dag dag = dag();
        List<DagStatus> statuses = captureRunStatuses();
        when(dagRepo.findByIdAndTenantId(DAG_ID, TENANT_ID)).thenReturn(Optional.of(dag));
        when(dagster.launch("old_job", "onelake", "onelake-loc"))
            .thenThrow(new RuntimeException("connection refused"));

        assertThatThrownBy(() -> service.triggerDag(DAG_ID, TriggerType.MANUAL))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("Dagster 触发失败");
        assertThat(statuses).containsExactly(DagStatus.QUEUED, DagStatus.FAILED);
        verify(runRepo, times(2)).save(any(JobRun.class));
    }

    @Test
    void triggerDwdModelDagCreatesModelRunAndLaunchesDagsterWithRunConfig() {
        Dag dag = dag();
        dag.setDagsterJob("onelake_dbt_model_run");
        dag.setDefinition("""
            {
              "kind": "DWD_MODEL_DAG",
              "modelId": "44444444-4444-4444-4444-444444444444",
              "dbtModelName": "dwd_orders",
              "sourceFqn": "ods.orders",
              "targetFqn": "dwd.orders",
              "artifactPath": "models/intermediate/dwd_orders.sql",
              "resourceGroup": "default",
              "computeProfile": "trino-small"
            }
            """);
        List<DagStatus> statuses = captureRunStatuses();
        when(dagRepo.findByIdAndTenantId(DAG_ID, TENANT_ID)).thenReturn(Optional.of(dag));
        when(jdbc.queryForMap(anyString(), eq(MODEL_ID), eq(TENANT_ID))).thenReturn(validatedModelRow());
        when(dagster.launch(eq("onelake_dbt_model_run"), eq("onelake"), eq("onelake-loc"), anyMap(), any()))
            .thenReturn("dagster-dwd-run");

        UUID runId = service.triggerDag(DAG_ID, TriggerType.MANUAL);

        assertThat(runId).isEqualTo(RUN_ID);
        assertThat(statuses).containsExactly(DagStatus.QUEUED, DagStatus.RUNNING);
        ArgumentCaptor<Map<String, Object>> configCaptor = ArgumentCaptor.forClass(Map.class);
        verify(dagster).launch(eq("onelake_dbt_model_run"), eq("onelake"), eq("onelake-loc"),
            configCaptor.capture(), any());
        @SuppressWarnings("unchecked")
        Map<String, Object> op = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) configCaptor.getValue()
            .get("ops")).get("run_dwd_model")).get("config");
        assertThat(op).containsEntry("model_name", "dwd_orders");
        assertThat(op).containsEntry("model_id", MODEL_ID.toString());
        assertThat(op).containsEntry("tenant_id", TENANT_ID.toString());
        assertThat(op).containsEntry("trigger_type", "MANUAL");
        assertThat(op.get("run_id")).asString().isNotBlank();
        verify(jdbc).update(startsWith("INSERT INTO modeling.model_run"), any(), eq(TENANT_ID), eq(MODEL_ID),
            eq("MANUAL"), eq(DAG_ID), eq("default"), eq("trino-small"),
            eq("models/intermediate/dwd_orders.sql"));
        verify(jdbc).update(startsWith("UPDATE modeling.data_model"), any(), eq(MODEL_ID), eq(TENANT_ID));
        verify(jdbc).update(startsWith("UPDATE modeling.model_run\nSET dagster_run_id"),
            eq("dagster-dwd-run"), any());
    }

    @Test
    void triggerDagRejectsDraftDagsterJobBeforeCreatingRun() {
        Dag dag = dag();
        dag.setDagsterJob("sql_workbench_draft");
        when(dagRepo.findByIdAndTenantId(DAG_ID, TENANT_ID)).thenReturn(Optional.of(dag));

        assertThatThrownBy(() -> service.triggerDag(DAG_ID, TriggerType.MANUAL))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("尚未绑定可执行 Dagster 作业");

        verifyNoInteractions(runRepo);
        verifyNoInteractions(dagster);
    }

    @Test
    void triggerDagRejectsSparkRuntimeContractBeforeCreatingRun() {
        Dag dag = dag();
        dag.setDagsterJob("onelake_spark_operator_run");
        dag.setDefinition("{\"compileTarget\":\"SPARK\",\"engine\":\"SPARK\",\"nodes\":[]}");
        when(dagRepo.findByIdAndTenantId(DAG_ID, TENANT_ID)).thenReturn(Optional.of(dag));
        when(runtimeContractService.triggerBlockedReason(eq("onelake_spark_operator_run"), anyMap()))
            .thenReturn(Optional.of("SPARK 仍处于 Manifest 契约态，尚未接入 Dagster Spark op、依赖隔离和部署契约"));

        assertThatThrownBy(() -> service.triggerDag(DAG_ID, TriggerType.MANUAL))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("SPARK 仍处于 Manifest 契约态");

        verifyNoInteractions(runRepo);
        verifyNoInteractions(dagster);
    }

    private Dag dag() {
        Dag dag = new Dag();
        dag.setId(DAG_ID);
        dag.setTenantId(TENANT_ID);
        dag.setName("old_pipeline");
        dag.setDagsterJob("old_job");
        dag.setDefinition("{\"nodes\":[]}");
        dag.setEnabled(true);
        dag.setVersion(2);
        return dag;
    }

    private JobRun jobRun(UUID dagId) {
        JobRun run = new JobRun();
        run.setId(RUN_ID);
        run.setDagId(dagId);
        run.setDagsterRunId("dagster-run-1");
        run.setTriggerType(TriggerType.MANUAL);
        run.setStatus(DagStatus.SUCCESS);
        run.setStartedAt(Instant.parse("2026-06-23T01:02:03Z"));
        run.setFinishedAt(Instant.parse("2026-06-23T01:03:04Z"));
        return run;
    }

    private Map<String, Object> validatedModelRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", MODEL_ID);
        row.put("tenant_id", TENANT_ID);
        row.put("status", "VALIDATED");
        row.put("dbt_model_name", "dwd_orders");
        row.put("source_fqn", "ods.orders");
        row.put("target_fqn", "dwd.orders");
        row.put("artifact_path", "models/intermediate/dwd_orders.sql");
        row.put("resource_group", "default");
        row.put("compute_profile", "trino-small");
        return row;
    }

    private List<DagStatus> captureRunStatuses() {
        List<DagStatus> statuses = new ArrayList<>();
        doAnswer(invocation -> {
            JobRun run = invocation.getArgument(0);
            if (run.getId() == null) {
                run.setId(RUN_ID);
            }
            statuses.add(run.getStatus());
            return run;
        }).when(runRepo).save(any(JobRun.class));
        return statuses;
    }
}
