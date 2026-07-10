package com.onelake.orchestration.service;

import com.onelake.common.context.TenantContext;
import com.onelake.common.outbox.DomainEvents;
import com.onelake.common.outbox.OutboxPublisher;
import com.onelake.orchestration.domain.entity.Dag;
import com.onelake.orchestration.domain.entity.JobRun;
import com.onelake.orchestration.domain.enums.DagStatus;
import com.onelake.orchestration.repository.DagRepository;
import com.onelake.orchestration.repository.JobRunRepository;
import com.onelake.orchestration.repository.SchedulerLockRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** {@link SlaMonitorService} 的超时取消、SLA 标记和事件幂等测试。 */
@ExtendWith(MockitoExtension.class)
class SlaMonitorServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-10T10:10:01Z");

    @Mock private JobRunRepository jobRunRepo;
    @Mock private DagRepository dagRepo;
    @Mock private SchedulerLockRepository schedulerLockRepo;
    @Mock private OrchestrationService orchestrationService;
    @Mock private OutboxPublisher outboxPublisher;

    private SlaMonitorRunProcessor processor;
    private SlaMonitorDecisionProcessor decisionProcessor;

    @BeforeEach
    void setUp() {
        decisionProcessor = new SlaMonitorDecisionProcessor(
                jobRunRepo, dagRepo, orchestrationService, outboxPublisher);
        processor = new SlaMonitorRunProcessor(
                jobRunRepo, dagRepo, orchestrationService, decisionProcessor);
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void timeoutCancelsRunAndPublishesTimeoutEvent() {
        Dag dag = dag(null, 5);
        JobRun run = activeRun(dag, NOW.minusSeconds(6 * 60L), false);
        stubRun(dag, run);

        processor.process(run.getId(), NOW);

        var order = inOrder(orchestrationService, jobRunRepo);
        order.verify(orchestrationService).refreshRunStatusForAutomation(run.getId());
        order.verify(jobRunRepo).findByIdForUpdate(run.getId());
        verify(orchestrationService).cancelRun(run.getId());
        ArgumentCaptor<Map<String, Object>> payload = payloadCaptor();
        verify(outboxPublisher).publish(
                eq(DomainEvents.PIPELINE_RUN_TIMEOUT), eq(dag.getId().toString()), payload.capture());
        assertRequiredPayload(payload.getValue(), dag, run, 6L);
        assertThat(payload.getValue()).containsEntry("timeoutMinutes", 5);
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    void slaBreachMarksRunAndPublishesSlaEvent() {
        Dag dag = dag(5, 30);
        JobRun run = activeRun(dag, NOW.minusSeconds(6 * 60L), false);
        stubRun(dag, run);

        processor.process(run.getId(), NOW);

        assertThat(run.getSlaMissed()).isTrue();
        assertThat(run.getUpdatedAt()).isEqualTo(NOW);
        verify(jobRunRepo).save(run);
        verify(orchestrationService, never()).cancelRun(any());
        ArgumentCaptor<Map<String, Object>> payload = payloadCaptor();
        verify(outboxPublisher).publish(
                eq(DomainEvents.PIPELINE_RUN_SLA_MISSED), eq(dag.getId().toString()), payload.capture());
        assertRequiredPayload(payload.getValue(), dag, run, 6L);
    }

    @Test
    void repeatedSlaScanPublishesEventOnlyOnce() {
        Dag dag = dag(5, 30);
        JobRun run = activeRun(dag, NOW.minusSeconds(6 * 60L), false);
        stubRun(dag, run);

        processor.process(run.getId(), NOW);
        processor.process(run.getId(), NOW.plusSeconds(60));

        verify(outboxPublisher, times(1)).publish(
                eq(DomainEvents.PIPELINE_RUN_SLA_MISSED), eq(dag.getId().toString()), any(Map.class));
        verify(jobRunRepo, times(1)).save(run);
    }

    @Test
    void terminalStateMakesTimeoutEventIdempotent() {
        Dag dag = dag(null, 5);
        JobRun run = activeRun(dag, NOW.minusSeconds(6 * 60L), false);
        stubRun(dag, run);
        doAnswer(invocation -> {
            run.setStatus(DagStatus.CANCELLED);
            run.setFinishedAt(NOW);
            return null;
        }).when(orchestrationService).cancelRun(run.getId());

        processor.process(run.getId(), NOW);
        processor.process(run.getId(), NOW.plusSeconds(60));

        verify(orchestrationService, times(1)).cancelRun(run.getId());
        verify(outboxPublisher, times(1)).publish(
                eq(DomainEvents.PIPELINE_RUN_TIMEOUT), eq(dag.getId().toString()), any(Map.class));
    }

    @Test
    void refreshedSuccessfulRunDoesNotProduceSlaOrTimeoutEvent() {
        Dag dag = dag(1, 1);
        JobRun run = activeRun(dag, NOW.minusSeconds(2 * 60L), false);
        stubRun(dag, run);
        doAnswer(invocation -> {
            run.setStatus(DagStatus.SUCCEEDED);
            run.setFinishedAt(NOW.minusSeconds(30));
            return run;
        }).when(orchestrationService).refreshRunStatusForAutomation(run.getId());

        processor.process(run.getId(), NOW);

        verify(orchestrationService, never()).cancelRun(run.getId());
        verify(jobRunRepo, never()).save(any(JobRun.class));
        verifyNoInteractions(outboxPublisher);
        assertThat(run.getSlaMissed()).isFalse();
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    void heldSchedulerLockSkipsCandidateScan() {
        SlaMonitorRunProcessor mockedProcessor = org.mockito.Mockito.mock(SlaMonitorRunProcessor.class);
        SlaMonitorService service = new SlaMonitorService(jobRunRepo, schedulerLockRepo, mockedProcessor);
        when(schedulerLockRepo.acquire(anyString(), anyString(), any())).thenReturn(0);

        service.tickSlaMonitor(NOW);

        verifyNoInteractions(jobRunRepo, mockedProcessor);
        verify(schedulerLockRepo, never()).release(anyString(), anyString());
    }

    @Test
    void acquiredSchedulerLockProcessesCandidatesAndReleasesLock() {
        UUID runId = UUID.randomUUID();
        SlaMonitorRunProcessor mockedProcessor = org.mockito.Mockito.mock(SlaMonitorRunProcessor.class);
        SlaMonitorService service = new SlaMonitorService(jobRunRepo, schedulerLockRepo, mockedProcessor);
        when(schedulerLockRepo.acquire(anyString(), anyString(), any())).thenReturn(1);
        when(schedulerLockRepo.release(anyString(), anyString())).thenReturn(1);
        when(jobRunRepo.findSlaMonitorCandidateIds(any())).thenReturn(List.of(runId));

        service.tickSlaMonitor(NOW);

        verify(mockedProcessor).process(runId, NOW);
        verify(schedulerLockRepo).release(anyString(), anyString());
    }

    private void stubRun(Dag dag, JobRun run) {
        when(jobRunRepo.findById(run.getId())).thenReturn(Optional.of(run));
        when(jobRunRepo.findByIdForUpdate(run.getId())).thenReturn(Optional.of(run));
        when(dagRepo.findById(dag.getId())).thenReturn(Optional.of(dag));
    }

    private Dag dag(Integer slaMinutes, Integer timeoutMinutes) {
        Dag dag = new Dag();
        dag.setId(UUID.randomUUID());
        dag.setTenantId(UUID.randomUUID());
        dag.setSlaMinutes(slaMinutes);
        dag.setTimeoutMinutes(timeoutMinutes);
        return dag;
    }

    private JobRun activeRun(Dag dag, Instant startedAt, boolean slaMissed) {
        JobRun run = new JobRun();
        run.setId(UUID.randomUUID());
        run.setDagId(dag.getId());
        run.setStatus(DagStatus.RUNNING);
        run.setStartedAt(startedAt);
        run.setLogicalDate(Instant.parse("2026-07-10T10:00:00Z"));
        run.setSlaMissed(slaMissed);
        return run;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ArgumentCaptor<Map<String, Object>> payloadCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
    }

    private void assertRequiredPayload(Map<String, Object> payload, Dag dag, JobRun run,
                                       long elapsedMinutes) {
        assertThat(payload)
                .containsEntry("pipelineId", dag.getId().toString())
                .containsEntry("runId", run.getId().toString())
                .containsEntry("logicalDate", run.getLogicalDate().toString())
                .containsEntry("slaMinutes", dag.getSlaMinutes())
                .containsEntry("elapsedMinutes", elapsedMinutes);
    }
}
