package com.onelake.orchestration.service;

import com.onelake.common.context.TenantContext;
import com.onelake.orchestration.domain.entity.Backfill;
import com.onelake.orchestration.domain.entity.BackfillRun;
import com.onelake.orchestration.domain.entity.Dag;
import com.onelake.orchestration.domain.entity.JobRun;
import com.onelake.orchestration.domain.enums.BackfillRunStatus;
import com.onelake.orchestration.domain.enums.BackfillStatus;
import com.onelake.orchestration.domain.enums.DagStatus;
import com.onelake.orchestration.domain.enums.TriggerType;
import com.onelake.orchestration.dto.BackfillDTO;
import com.onelake.orchestration.dto.JobRunDTO;
import com.onelake.orchestration.repository.BackfillRepository;
import com.onelake.orchestration.repository.BackfillRunRepository;
import com.onelake.orchestration.repository.DagRepository;
import com.onelake.orchestration.repository.JobRunRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BackfillServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DAG_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID BACKFILL_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID RUN_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID CREATOR_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");

    @Mock private BackfillRepository backfillRepo;
    @Mock private BackfillRunRepository backfillRunRepo;
    @Mock private DagRepository dagRepo;
    @Mock private JobRunRepository jobRunRepo;
    @Mock private OrchestrationService orchestrationService;

    private BackfillService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT_ID);
        TenantContext.setUserId(UUID.randomUUID());
        service = new BackfillService(
                backfillRepo, backfillRunRepo, dagRepo, jobRunRepo, orchestrationService);
        lenient().when(backfillRepo.save(any(Backfill.class))).thenAnswer(invocation -> {
            Backfill backfill = invocation.getArgument(0);
            if (backfill.getId() == null) {
                backfill.setId(BACKFILL_ID);
            }
            return backfill;
        });
        lenient().when(backfillRunRepo.save(any(BackfillRun.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void createBackfillExpandsInclusiveDateRangeByGrain() {
        List<BackfillRun> plannedRuns = new ArrayList<>();
        when(dagRepo.findByIdAndTenantId(DAG_ID, TENANT_ID)).thenReturn(Optional.of(dag()));
        when(backfillRunRepo.saveAll(any())).thenAnswer(invocation -> {
            Iterable<BackfillRun> runs = invocation.getArgument(0);
            runs.forEach(plannedRuns::add);
            return plannedRuns;
        });

        BackfillDTO dto = service.createBackfill(
                DAG_ID,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-03T00:00:00Z"),
                "DAY",
                2);

        assertThat(dto.totalRuns()).isEqualTo(3);
        assertThat(dto.maxParallel()).isEqualTo(2);
        assertThat(dto.timezone()).isEqualTo("Asia/Shanghai");
        assertThat(plannedRuns).hasSize(3);
        assertThat(plannedRuns).extracting(BackfillRun::getLogicalDate).containsExactly(
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-02T00:00:00Z"),
                Instant.parse("2026-01-03T00:00:00Z"));
        assertThat(plannedRuns).extracting(BackfillRun::getDataIntervalEnd).containsExactly(
                Instant.parse("2026-01-02T00:00:00Z"),
                Instant.parse("2026-01-03T00:00:00Z"),
                Instant.parse("2026-01-04T00:00:00Z"));
    }

    @Test
    void createBackfillExpandsDailyWindowInFrozenDagTimezone() {
        Dag dag = dag();
        dag.setTimezone("America/New_York");
        List<BackfillRun> plannedRuns = new ArrayList<>();
        when(dagRepo.findByIdAndTenantId(DAG_ID, TENANT_ID)).thenReturn(Optional.of(dag));
        when(backfillRunRepo.saveAll(any())).thenAnswer(invocation -> {
            Iterable<BackfillRun> runs = invocation.getArgument(0);
            runs.forEach(plannedRuns::add);
            return plannedRuns;
        });

        BackfillDTO dto = service.createBackfill(
                DAG_ID,
                Instant.parse("2026-03-08T05:00:00Z"),
                Instant.parse("2026-03-08T05:00:00Z"),
                "DAY",
                1);

        ArgumentCaptor<Backfill> backfillCaptor = ArgumentCaptor.forClass(Backfill.class);
        verify(backfillRepo).save(backfillCaptor.capture());
        assertThat(backfillCaptor.getValue().getTimezone()).isEqualTo("America/New_York");
        assertThat(dto.timezone()).isEqualTo("America/New_York");
        assertThat(plannedRuns).singleElement()
                .extracting(BackfillRun::getDataIntervalEnd)
                .isEqualTo(Instant.parse("2026-03-09T04:00:00Z"));
    }

    @Test
    void createCatchupBackfillPersistsOnlyExactCronWindows() {
        List<BackfillRun> plannedRuns = new ArrayList<>();
        when(dagRepo.findByIdAndTenantId(DAG_ID, TENANT_ID)).thenReturn(Optional.of(dag()));
        when(backfillRunRepo.saveAll(any())).thenAnswer(invocation -> {
            Iterable<BackfillRun> runs = invocation.getArgument(0);
            runs.forEach(plannedRuns::add);
            return plannedRuns;
        });
        List<DataIntervalCalculator.DataInterval> intervals = List.of(
                new DataIntervalCalculator.DataInterval(
                        Instant.parse("2026-01-01T00:00:00Z"),
                        Instant.parse("2026-01-01T00:00:00Z"),
                        Instant.parse("2026-01-02T00:00:00Z")),
                new DataIntervalCalculator.DataInterval(
                        Instant.parse("2026-01-08T00:00:00Z"),
                        Instant.parse("2026-01-08T00:00:00Z"),
                        Instant.parse("2026-01-09T00:00:00Z")));

        BackfillDTO dto = service.createCatchupBackfill(DAG_ID, intervals, "DAY", 2);

        assertThat(dto.totalRuns()).isEqualTo(2);
        assertThat(plannedRuns)
                .extracting(BackfillRun::getLogicalDate)
                .containsExactly(
                        Instant.parse("2026-01-01T00:00:00Z"),
                        Instant.parse("2026-01-08T00:00:00Z"));
    }

    @Test
    void listJobRunsValidatesTenantBackfillAndDelegatesPagedQuery() {
        Backfill backfill = backfill(BackfillStatus.RUNNING, 2);
        PageRequest pageable = PageRequest.of(0, 20);
        Page<JobRunDTO> expected = Page.empty(pageable);
        when(backfillRepo.findByIdAndTenantId(BACKFILL_ID, TENANT_ID)).thenReturn(Optional.of(backfill));
        when(orchestrationService.listBackfillRuns(DAG_ID, BACKFILL_ID, pageable)).thenReturn(expected);

        Page<JobRunDTO> result = service.listJobRuns(BACKFILL_ID, pageable);

        assertThat(result).isSameAs(expected);
        verify(orchestrationService).listBackfillRuns(DAG_ID, BACKFILL_ID, pageable);
    }

    @Test
    void getJobRunValidatesTenantBackfillAndDelegatesScopedLookup() {
        Backfill backfill = backfill(BackfillStatus.RUNNING, 2);
        JobRunDTO expected = new JobRunDTO(
                RUN_ID, DAG_ID, "orders_pipeline", "onelake_pipeline_run", "dagster-run-1",
                "BACKFILL", "RUNNING", "NORMAL", "Asia/Shanghai", Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-02T00:00:00Z"),
                BACKFILL_ID, Instant.parse("2026-01-01T00:00:00Z"), null, CREATOR_ID, "operator",
                false, null, 0, null, null);
        when(backfillRepo.findByIdAndTenantId(BACKFILL_ID, TENANT_ID)).thenReturn(Optional.of(backfill));
        when(orchestrationService.getBackfillRun(DAG_ID, BACKFILL_ID, RUN_ID)).thenReturn(expected);

        JobRunDTO result = service.getJobRun(BACKFILL_ID, RUN_ID);

        assertThat(result).isSameAs(expected);
        verify(orchestrationService).getBackfillRun(DAG_ID, BACKFILL_ID, RUN_ID);
    }

    @Test
    void dispatchBackfillHonorsMaxParallelSlots() {
        Backfill backfill = backfill(BackfillStatus.RUNNING, 2);
        backfill.setCreatedBy(CREATOR_ID);
        backfill.setCreatedByName("backfill-owner");
        BackfillRun alreadyRunning = backfillRun(Instant.parse("2026-01-01T00:00:00Z"), BackfillRunStatus.RUNNING);
        alreadyRunning.setJobRunId(UUID.randomUUID());
        BackfillRun queuedA = backfillRun(Instant.parse("2026-01-02T00:00:00Z"), BackfillRunStatus.QUEUED);
        BackfillRun queuedB = backfillRun(Instant.parse("2026-01-03T00:00:00Z"), BackfillRunStatus.QUEUED);
        List<BackfillRun> allRuns = List.of(alreadyRunning, queuedA, queuedB);
        JobRun runningRun = new JobRun();
        runningRun.setId(alreadyRunning.getJobRunId());
        runningRun.setStatus(DagStatus.RUNNING);

        when(backfillRepo.findByIdForUpdate(BACKFILL_ID)).thenReturn(Optional.of(backfill));
        when(backfillRunRepo.findByBackfillIdForUpdate(BACKFILL_ID)).thenReturn(allRuns);
        when(backfillRunRepo.countByBackfillIdAndStatus(BACKFILL_ID, BackfillRunStatus.RUNNING)).thenReturn(1L);
        when(backfillRunRepo.findByBackfillIdAndStatusForUpdate(
                eq(BACKFILL_ID), eq(BackfillRunStatus.QUEUED), any(Pageable.class)))
                .thenReturn(List.of(queuedA));
        when(orchestrationService.refreshRunStatusForBackfill(alreadyRunning.getJobRunId())).thenReturn(runningRun);
        when(orchestrationService.triggerPipelineRun(eq(DAG_ID), eq(TriggerType.BACKFILL), any(RunContext.class)))
                .thenAnswer(invocation -> {
                    assertThat(TenantContext.getTenantId()).isEqualTo(TENANT_ID);
                    assertThat(TenantContext.getUserId()).isEqualTo(CREATOR_ID);
                    assertThat(TenantContext.getUsername()).isEqualTo("backfill-owner");
                    return RUN_ID;
                });

        TenantContext.clear();

        int dispatched = service.dispatchBackfill(BACKFILL_ID);

        assertThat(dispatched).isEqualTo(1);
        assertThat(queuedA.getJobRunId()).isEqualTo(RUN_ID);
        assertThat(queuedA.getStatus()).isEqualTo(BackfillRunStatus.RUNNING);
        assertThat(queuedB.getJobRunId()).isNull();
        assertThat(TenantContext.getTenantId()).isNull();
        assertThat(TenantContext.getUserId()).isNull();
        assertThat(TenantContext.getUsername()).isNull();
        verify(orchestrationService).triggerPipelineRun(
                eq(DAG_ID), eq(TriggerType.BACKFILL), any(RunContext.class));
    }

    @Test
    void dispatchBackfillAlsoHonorsDagMaxActiveRuns() {
        Backfill backfill = backfill(BackfillStatus.QUEUED, 2);
        BackfillRun queued = backfillRun(
                Instant.parse("2026-01-01T00:00:00Z"), BackfillRunStatus.QUEUED);
        Dag dag = dag();
        dag.setMaxActiveRuns(1);

        when(backfillRepo.findByIdForUpdate(BACKFILL_ID)).thenReturn(Optional.of(backfill));
        when(backfillRunRepo.findByBackfillIdForUpdate(BACKFILL_ID)).thenReturn(List.of(queued));
        when(backfillRunRepo.countByBackfillIdAndStatus(BACKFILL_ID, BackfillRunStatus.RUNNING))
                .thenReturn(0L);
        when(dagRepo.findByIdAndTenantId(DAG_ID, TENANT_ID)).thenReturn(Optional.of(dag));
        when(jobRunRepo.countByDagIdAndStatusIn(eq(DAG_ID), any())).thenReturn(1L);

        assertThat(service.dispatchBackfill(BACKFILL_ID)).isZero();

        verify(orchestrationService, never()).triggerPipelineRun(
                eq(DAG_ID), eq(TriggerType.BACKFILL), any(RunContext.class));
    }

    @Test
    void cancelBackfillStopsQueuedDispatchAndCancelsRunningRuns() {
        Backfill backfill = backfill(BackfillStatus.RUNNING, 2);
        BackfillRun queued = backfillRun(Instant.parse("2026-01-01T00:00:00Z"), BackfillRunStatus.QUEUED);
        BackfillRun running = backfillRun(Instant.parse("2026-01-02T00:00:00Z"), BackfillRunStatus.RUNNING);
        running.setJobRunId(RUN_ID);
        BackfillRun succeeded = backfillRun(Instant.parse("2026-01-03T00:00:00Z"), BackfillRunStatus.SUCCEEDED);
        List<BackfillRun> allRuns = List.of(queued, running, succeeded);

        when(backfillRepo.findByIdAndTenantIdForUpdate(BACKFILL_ID, TENANT_ID)).thenReturn(Optional.of(backfill));
        when(backfillRunRepo.findByBackfillIdForUpdate(BACKFILL_ID)).thenReturn(allRuns);

        BackfillDTO dto = service.cancelBackfill(BACKFILL_ID);

        assertThat(dto.status()).isEqualTo("CANCELLED");
        assertThat(queued.getStatus()).isEqualTo(BackfillRunStatus.CANCELLED);
        assertThat(running.getStatus()).isEqualTo(BackfillRunStatus.CANCELLED);
        assertThat(succeeded.getStatus()).isEqualTo(BackfillRunStatus.SUCCEEDED);
        verify(orchestrationService).cancelRun(RUN_ID);

        when(backfillRepo.findByIdForUpdate(BACKFILL_ID)).thenReturn(Optional.of(backfill));
        assertThat(service.dispatchBackfill(BACKFILL_ID)).isZero();
        verify(orchestrationService, never()).triggerPipelineRun(
                eq(DAG_ID), eq(TriggerType.BACKFILL), any(RunContext.class));
    }

    @Test
    void dispatchBackfillCountsExternallyCancelledChildRunsAsFailedProgress() {
        Backfill backfill = backfill(BackfillStatus.RUNNING, 2);
        BackfillRun succeeded = backfillRun(Instant.parse("2026-01-01T00:00:00Z"), BackfillRunStatus.SUCCEEDED);
        BackfillRun cancelled = backfillRun(Instant.parse("2026-01-02T00:00:00Z"), BackfillRunStatus.RUNNING);
        cancelled.setJobRunId(RUN_ID);
        List<BackfillRun> allRuns = List.of(succeeded, cancelled);
        backfill.setTotalRuns(allRuns.size());
        JobRun cancelledJobRun = new JobRun();
        cancelledJobRun.setId(RUN_ID);
        cancelledJobRun.setStatus(DagStatus.CANCELLED);

        when(backfillRepo.findByIdForUpdate(BACKFILL_ID)).thenReturn(Optional.of(backfill));
        when(backfillRunRepo.findByBackfillIdForUpdate(BACKFILL_ID)).thenReturn(allRuns);
        when(orchestrationService.refreshRunStatusForBackfill(RUN_ID)).thenReturn(cancelledJobRun);

        int dispatched = service.dispatchBackfill(BACKFILL_ID);

        assertThat(dispatched).isZero();
        assertThat(cancelled.getStatus()).isEqualTo(BackfillRunStatus.CANCELLED);
        assertThat(backfill.getSucceededRuns()).isEqualTo(1);
        assertThat(backfill.getFailedRuns()).isEqualTo(1);
        assertThat(backfill.getStatus()).isEqualTo(BackfillStatus.PARTIAL);
    }

    @Test
    void dispatchBackfillWaitsForAndThenFollowsAutomaticRetryChild() {
        Backfill backfill = backfill(BackfillStatus.RUNNING, 1);
        backfill.setTotalRuns(1);
        BackfillRun backfillRun = backfillRun(
                Instant.parse("2026-01-01T00:00:00Z"), BackfillRunStatus.RUNNING);
        backfillRun.setJobRunId(RUN_ID);
        Dag dag = dag();
        dag.setRunRetryCount(1);
        JobRun sourceFailure = new JobRun();
        sourceFailure.setId(RUN_ID);
        sourceFailure.setDagId(DAG_ID);
        sourceFailure.setStatus(DagStatus.FAILED);
        sourceFailure.setRunRetryAttempt(0);
        UUID retryRunId = UUID.randomUUID();
        JobRun retryRun = new JobRun();
        retryRun.setId(retryRunId);
        retryRun.setDagId(DAG_ID);
        retryRun.setStatus(DagStatus.RUNNING);
        retryRun.setRunRetryAttempt(1);

        when(backfillRepo.findByIdForUpdate(BACKFILL_ID)).thenReturn(Optional.of(backfill));
        when(backfillRunRepo.findByBackfillIdForUpdate(BACKFILL_ID)).thenReturn(List.of(backfillRun));
        when(dagRepo.findByIdAndTenantId(DAG_ID, TENANT_ID)).thenReturn(Optional.of(dag));
        when(orchestrationService.refreshRunStatusForBackfill(RUN_ID)).thenReturn(sourceFailure);
        when(jobRunRepo.findFirstByRetrySourceRunIdOrderByStartedAtDesc(RUN_ID))
                .thenReturn(Optional.empty(), Optional.of(retryRun));
        when(orchestrationService.refreshRunStatusForBackfill(retryRunId)).thenReturn(retryRun);
        when(backfillRunRepo.countByBackfillIdAndStatus(BACKFILL_ID, BackfillRunStatus.RUNNING))
                .thenReturn(1L);
        when(jobRunRepo.countByDagIdAndStatusIn(eq(DAG_ID), any())).thenReturn(1L);

        assertThat(service.dispatchBackfill(BACKFILL_ID)).isZero();
        assertThat(backfillRun.getStatus()).isEqualTo(BackfillRunStatus.RUNNING);
        assertThat(backfillRun.getJobRunId()).isEqualTo(RUN_ID);

        sourceFailure.setRetryDispatchedAt(Instant.now());
        assertThat(service.dispatchBackfill(BACKFILL_ID)).isZero();
        assertThat(backfillRun.getStatus()).isEqualTo(BackfillRunStatus.RUNNING);
        assertThat(backfillRun.getJobRunId()).isEqualTo(retryRunId);
        assertThat(backfill.getStatus()).isEqualTo(BackfillStatus.RUNNING);
        verify(orchestrationService).refreshRunStatusForBackfill(retryRunId);
    }

    @Test
    void dataEngineerBackfillWorkflowHonorsDateOrderParallelLimitAndCancel() {
        Backfill serialBackfill = backfill(BackfillStatus.QUEUED, 1);
        List<BackfillRun> serialRuns = List.of(
                backfillRun(Instant.parse("2026-01-01T00:00:00Z"), BackfillRunStatus.QUEUED),
                backfillRun(Instant.parse("2026-01-02T00:00:00Z"), BackfillRunStatus.QUEUED),
                backfillRun(Instant.parse("2026-01-03T00:00:00Z"), BackfillRunStatus.QUEUED));
        UUID run1 = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1");
        UUID run2 = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2");
        UUID run3 = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa3");

        stubMutableBackfillRuns(serialBackfill, serialRuns);
        when(orchestrationService.triggerPipelineRun(eq(DAG_ID), eq(TriggerType.BACKFILL), any(RunContext.class)))
                .thenReturn(run1, run2, run3);

        assertThat(service.dispatchBackfill(BACKFILL_ID)).isEqualTo(1);
        serialRuns.get(0).setStatus(BackfillRunStatus.SUCCEEDED);
        assertThat(service.dispatchBackfill(BACKFILL_ID)).isEqualTo(1);
        serialRuns.get(1).setStatus(BackfillRunStatus.SUCCEEDED);
        assertThat(service.dispatchBackfill(BACKFILL_ID)).isEqualTo(1);

        ArgumentCaptor<RunContext> contextCaptor = ArgumentCaptor.forClass(RunContext.class);
        verify(orchestrationService, times(3)).triggerPipelineRun(
                eq(DAG_ID), eq(TriggerType.BACKFILL), contextCaptor.capture());
        assertThat(contextCaptor.getAllValues())
                .extracting(RunContext::logicalDate)
                .containsExactly(
                        Instant.parse("2026-01-01T00:00:00Z"),
                        Instant.parse("2026-01-02T00:00:00Z"),
                        Instant.parse("2026-01-03T00:00:00Z"));
        assertThat(contextCaptor.getAllValues())
                .extracting(RunContext::dataIntervalEnd)
                .containsExactly(
                        Instant.parse("2026-01-02T00:00:00Z"),
                        Instant.parse("2026-01-03T00:00:00Z"),
                        Instant.parse("2026-01-04T00:00:00Z"));
        assertThat(contextCaptor.getAllValues())
                .extracting(RunContext::timezone)
                .containsOnly("Asia/Shanghai");
        assertThat(serialRuns)
                .extracting(BackfillRun::getJobRunId)
                .containsExactly(run1, run2, run3);

        Backfill parallelBackfill = backfill(BackfillStatus.QUEUED, 2);
        List<BackfillRun> parallelRuns = List.of(
                backfillRun(Instant.parse("2026-01-01T00:00:00Z"), BackfillRunStatus.QUEUED),
                backfillRun(Instant.parse("2026-01-02T00:00:00Z"), BackfillRunStatus.QUEUED),
                backfillRun(Instant.parse("2026-01-03T00:00:00Z"), BackfillRunStatus.QUEUED));
        UUID parallelRun1 = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb1");
        UUID parallelRun2 = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb2");
        stubMutableBackfillRuns(parallelBackfill, parallelRuns);
        when(orchestrationService.triggerPipelineRun(eq(DAG_ID), eq(TriggerType.BACKFILL), any(RunContext.class)))
                .thenReturn(parallelRun1, parallelRun2);

        assertThat(service.dispatchBackfill(BACKFILL_ID)).isEqualTo(2);
        assertThat(parallelRuns)
                .extracting(BackfillRun::getStatus)
                .containsExactly(
                        BackfillRunStatus.RUNNING,
                        BackfillRunStatus.RUNNING,
                        BackfillRunStatus.QUEUED);
        assertThat(parallelRuns.stream()
                .filter(run -> run.getStatus() == BackfillRunStatus.RUNNING)
                .count()).isEqualTo(2);

        when(backfillRepo.findByIdAndTenantIdForUpdate(BACKFILL_ID, TENANT_ID))
                .thenReturn(Optional.of(parallelBackfill));
        BackfillDTO cancelled = service.cancelBackfill(BACKFILL_ID);

        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.succeededRuns()).isZero();
        assertThat(cancelled.failedRuns()).isEqualTo(3);
        assertThat(parallelRuns)
                .extracting(BackfillRun::getStatus)
                .containsExactly(
                        BackfillRunStatus.CANCELLED,
                        BackfillRunStatus.CANCELLED,
                        BackfillRunStatus.CANCELLED);
        verify(orchestrationService).cancelRun(parallelRun1);
        verify(orchestrationService).cancelRun(parallelRun2);

        assertThat(service.dispatchBackfill(BACKFILL_ID)).isZero();
    }

    private Dag dag() {
        Dag dag = new Dag();
        dag.setId(DAG_ID);
        dag.setTenantId(TENANT_ID);
        dag.setName("orders_pipeline");
        dag.setDagsterJob("onelake_pipeline_run");
        dag.setDefinition("{}");
        dag.setPartitionGrain("DAY");
        return dag;
    }

    private Backfill backfill(BackfillStatus status, int maxParallel) {
        Backfill backfill = new Backfill();
        backfill.setId(BACKFILL_ID);
        backfill.setTenantId(TENANT_ID);
        backfill.setDagId(DAG_ID);
        backfill.setRangeStart(Instant.parse("2026-01-01T00:00:00Z"));
        backfill.setRangeEnd(Instant.parse("2026-01-03T00:00:00Z"));
        backfill.setGrain("DAY");
        backfill.setStatus(status);
        backfill.setTotalRuns(3);
        backfill.setMaxParallel(maxParallel);
        return backfill;
    }

    private BackfillRun backfillRun(Instant logicalDate, BackfillRunStatus status) {
        BackfillRun run = new BackfillRun();
        run.setId(UUID.randomUUID());
        run.setTenantId(TENANT_ID);
        run.setBackfillId(BACKFILL_ID);
        run.setDagId(DAG_ID);
        run.setLogicalDate(logicalDate);
        run.setDataIntervalStart(logicalDate);
        run.setDataIntervalEnd(logicalDate.plus(1, java.time.temporal.ChronoUnit.DAYS));
        run.setStatus(status);
        return run;
    }

    private void stubMutableBackfillRuns(Backfill backfill, List<BackfillRun> runs) {
        when(backfillRepo.findByIdForUpdate(BACKFILL_ID)).thenReturn(Optional.of(backfill));
        when(backfillRunRepo.findByBackfillIdForUpdate(BACKFILL_ID)).thenReturn(runs);
        when(backfillRunRepo.countByBackfillIdAndStatus(BACKFILL_ID, BackfillRunStatus.RUNNING))
                .thenAnswer(invocation -> runs.stream()
                        .filter(run -> run.getStatus() == BackfillRunStatus.RUNNING)
                        .count());
        when(backfillRunRepo.findByBackfillIdAndStatusForUpdate(
                eq(BACKFILL_ID), eq(BackfillRunStatus.QUEUED), any(Pageable.class)))
                .thenAnswer(invocation -> {
                    Pageable pageable = invocation.getArgument(2);
                    return runs.stream()
                            .filter(run -> run.getStatus() == BackfillRunStatus.QUEUED)
                            .limit(pageable.getPageSize())
                            .toList();
                });
    }
}
