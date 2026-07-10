package com.onelake.orchestration.service;

import com.onelake.common.context.TenantContext;
import com.onelake.orchestration.domain.entity.Dag;
import com.onelake.orchestration.domain.entity.JobRun;
import com.onelake.orchestration.domain.enums.DagStatus;
import com.onelake.orchestration.repository.DagRepository;
import com.onelake.orchestration.repository.JobRunRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@link PipelineRunRetryService} 的次数、间隔和独立重跑链测试。 */
@ExtendWith(MockitoExtension.class)
class PipelineRunRetryServiceTest {

    @Mock private JobRunRepository runRepo;
    @Mock private DagRepository dagRepo;
    @Mock private OrchestrationService orchestrationService;

    @Test
    void automaticRetryStopsAtDagRetryLimit() {
        UUID dagId = UUID.randomUUID();
        Instant failedAt = Instant.parse("2026-07-10T01:00:00Z");
        Dag dag = dag(dagId, 1, 0);
        JobRun initialFailure = failedRun(dagId, 0, failedAt);
        JobRun retryFailure = failedRun(dagId, 1, failedAt.plusSeconds(1));
        when(runRepo.findByIdForUpdate(initialFailure.getId())).thenReturn(Optional.of(initialFailure));
        when(runRepo.findByIdForUpdate(retryFailure.getId())).thenReturn(Optional.of(retryFailure));
        when(dagRepo.findByIdForUpdate(dagId)).thenReturn(Optional.of(dag));
        when(runRepo.countByDagIdAndStatusIn(any(), any())).thenReturn(0L);
        when(orchestrationService.triggerPipelineRetry(initialFailure)).thenReturn(UUID.randomUUID());
        PipelineRunRetryService service = new PipelineRunRetryService(runRepo, dagRepo, orchestrationService);

        boolean firstDispatched = service.retryIfDue(initialFailure.getId(), failedAt.plusSeconds(2));
        boolean secondDispatched = service.retryIfDue(retryFailure.getId(), failedAt.plusSeconds(3));

        assertThat(firstDispatched).isTrue();
        assertThat(secondDispatched).isFalse();
        assertThat(initialFailure.getRetryDispatchedAt()).isEqualTo(failedAt.plusSeconds(2));
        assertThat(retryFailure.getRetryDispatchedAt()).isEqualTo(failedAt.plusSeconds(3));
        verify(orchestrationService, times(1)).triggerPipelineRetry(initialFailure);
        var dispatchOrder = inOrder(runRepo, dagRepo, orchestrationService);
        dispatchOrder.verify(runRepo).findByIdForUpdate(initialFailure.getId());
        dispatchOrder.verify(dagRepo).findByIdForUpdate(dagId);
        dispatchOrder.verify(runRepo).countByDagIdAndStatusIn(any(), any());
        dispatchOrder.verify(orchestrationService).triggerPipelineRetry(initialFailure);
    }

    @Test
    void automaticRetryWaitsForConfiguredInterval() {
        UUID dagId = UUID.randomUUID();
        Instant failedAt = Instant.parse("2026-07-10T01:00:00Z");
        Dag dag = dag(dagId, 1, 60);
        JobRun failure = failedRun(dagId, 0, failedAt);
        when(runRepo.findByIdForUpdate(failure.getId())).thenReturn(Optional.of(failure));
        when(dagRepo.findByIdForUpdate(dagId)).thenReturn(Optional.of(dag));
        PipelineRunRetryService service = new PipelineRunRetryService(runRepo, dagRepo, orchestrationService);

        boolean dispatched = service.retryIfDue(failure.getId(), failedAt.plusSeconds(59));

        assertThat(dispatched).isFalse();
        assertThat(failure.getRetryDispatchedAt()).isNull();
        verify(orchestrationService, never()).triggerPipelineRetry(any());
    }

    @Test
    void backgroundRefreshRestoresRunTenantContext() {
        UUID previousTenant = UUID.randomUUID();
        UUID runTenant = UUID.randomUUID();
        UUID dagId = UUID.randomUUID();
        JobRun run = failedRun(dagId, 0, Instant.now());
        run.setStatus(DagStatus.RUNNING);
        run.setTriggeredBy(UUID.randomUUID());
        run.setTriggeredByName("retry-owner");
        Dag dag = dag(dagId, 1, 0);
        dag.setTenantId(runTenant);
        when(runRepo.findById(run.getId())).thenReturn(Optional.of(run));
        when(dagRepo.findById(dagId)).thenReturn(Optional.of(dag));
        doAnswer(invocation -> {
            assertThat(TenantContext.getTenantId()).isEqualTo(runTenant);
            assertThat(TenantContext.getUserId()).isEqualTo(run.getTriggeredBy());
            assertThat(TenantContext.getUsername()).isEqualTo("retry-owner");
            return run;
        }).when(orchestrationService).refreshRunStatusForAutomation(run.getId());
        PipelineRunRetryService service = new PipelineRunRetryService(runRepo, dagRepo, orchestrationService);
        TenantContext.setTenantId(previousTenant);

        try {
            service.refreshRunStatus(run.getId());
            assertThat(TenantContext.getTenantId()).isEqualTo(previousTenant);
        } finally {
            TenantContext.clear();
        }
    }

    private Dag dag(UUID dagId, int retries, int intervalSeconds) {
        Dag dag = new Dag();
        dag.setId(dagId);
        dag.setTenantId(UUID.randomUUID());
        dag.setScheduleMode("NORMAL");
        dag.setRunRetryCount(retries);
        dag.setRunRetryIntervalSeconds(intervalSeconds);
        dag.setMaxActiveRuns(1);
        return dag;
    }

    private JobRun failedRun(UUID dagId, int retryAttempt, Instant failedAt) {
        JobRun run = new JobRun();
        run.setId(UUID.randomUUID());
        run.setDagId(dagId);
        run.setStatus(DagStatus.FAILED);
        run.setRunRetryAttempt(retryAttempt);
        run.setFinishedAt(failedAt);
        return run;
    }
}
