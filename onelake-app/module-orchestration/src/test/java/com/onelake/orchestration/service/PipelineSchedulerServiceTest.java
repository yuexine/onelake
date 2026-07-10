package com.onelake.orchestration.service;

import com.onelake.common.context.TenantContext;
import com.onelake.orchestration.domain.entity.Dag;
import com.onelake.orchestration.domain.enums.TriggerType;
import com.onelake.orchestration.repository.DagRepository;
import com.onelake.orchestration.repository.SchedulerLockRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link PipelineSchedulerService} 的 cron 评估与多副本幂等触发测试。
 */
@ExtendWith(MockitoExtension.class)
class PipelineSchedulerServiceTest {

    @Mock private DagRepository dagRepo;
    @Mock private SchedulerLockRepository schedulerLockRepo;
    @Mock private OrchestrationService orchestrationService;

    private PipelineSchedulerService service;

    @BeforeEach
    void setup() {
        service = new PipelineSchedulerService(dagRepo, schedulerLockRepo, orchestrationService);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void isCronDueReturnsTrueWhenCronMatchesNowMinute() {
        Dag dag = new Dag();
        dag.setScheduleCron("0 * * * * *");  // 每分钟第 0 秒触发。
        ZonedDateTime prev = ZonedDateTime.of(2026, 6, 24, 10, 0, 0, 0, ZoneId.systemDefault());
        ZonedDateTime now = ZonedDateTime.of(2026, 6, 24, 10, 1, 0, 0, ZoneId.systemDefault());
        assertThat(PipelineSchedulerService.isCronDue(dag, prev, now)).isTrue();
    }

    @Test
    void isCronDueReturnsFalseWhenNoMatchInInterval() {
        Dag dag = new Dag();
        dag.setScheduleCron("0 0 0 1 1 *");  // 仅每年 1 月 1 日触发。
        ZonedDateTime prev = ZonedDateTime.of(2026, 6, 24, 10, 0, 0, 0, ZoneId.systemDefault());
        ZonedDateTime now = ZonedDateTime.of(2026, 6, 24, 10, 1, 0, 0, ZoneId.systemDefault());
        assertThat(PipelineSchedulerService.isCronDue(dag, prev, now)).isFalse();
    }

    @Test
    void isCronDueReturnsFalseForEmptyCron() {
        Dag dag = new Dag();
        dag.setScheduleCron("");
        ZonedDateTime prev = ZonedDateTime.now().minusMinutes(1);
        ZonedDateTime now = ZonedDateTime.now();
        assertThat(PipelineSchedulerService.isCronDue(dag, prev, now)).isFalse();
    }

    @Test
    void isCronDueReturnsFalseForInvalidCron() {
        Dag dag = new Dag();
        dag.setScheduleCron("not a valid cron");
        ZonedDateTime prev = ZonedDateTime.now().minusMinutes(1);
        ZonedDateTime now = ZonedDateTime.now();
        assertThat(PipelineSchedulerService.isCronDue(dag, prev, now)).isFalse();
    }

    @Test
    void isCronDueHandlesHourlyCron() {
        Dag dag = new Dag();
        dag.setScheduleCron("0 0 * * * *");  // 每小时第 0 分钟触发。
        // 区间包含 10:00，应该到期。
        ZonedDateTime prev1 = ZonedDateTime.of(2026, 6, 24, 9, 59, 0, 0, ZoneId.systemDefault());
        ZonedDateTime now1 = ZonedDateTime.of(2026, 6, 24, 10, 0, 0, 0, ZoneId.systemDefault());
        assertThat(PipelineSchedulerService.isCronDue(dag, prev1, now1)).isTrue();
        // 区间不包含 10:00，不应该到期。
        ZonedDateTime prev2 = ZonedDateTime.of(2026, 6, 24, 10, 15, 0, 0, ZoneId.systemDefault());
        ZonedDateTime now2 = ZonedDateTime.of(2026, 6, 24, 10, 16, 0, 0, ZoneId.systemDefault());
        assertThat(PipelineSchedulerService.isCronDue(dag, prev2, now2)).isFalse();
    }

    @Test
    void sameLogicalDateSecondTriggerIsDeduplicated() {
        Dag dag = scheduledPipeline();
        Instant tickAt = Instant.parse("2026-06-24T10:01:45Z");
        Instant expectedLogicalDate = tickAt.atZone(ZoneId.systemDefault())
                .withSecond(0).withNano(0).toInstant();
        when(schedulerLockRepo.acquire(anyString(), anyString(), any())).thenReturn(1);
        when(schedulerLockRepo.release(anyString(), anyString())).thenReturn(1);
        when(dagRepo.findByEnabledTrue()).thenReturn(List.of(dag));
        when(orchestrationService.triggerPipelineRun(eq(dag.getId()), eq(TriggerType.CRON), any()))
                .thenReturn(UUID.randomUUID())
                .thenThrow(new DataIntegrityViolationException("uq_job_run_cron_logical"));

        assertThatCode(() -> {
            service.tickScheduledPipelines(tickAt);
            service.tickScheduledPipelines(tickAt);
        }).doesNotThrowAnyException();

        ArgumentCaptor<OrchestrationService.PipelineRunOptions> optionsCaptor =
                ArgumentCaptor.forClass(OrchestrationService.PipelineRunOptions.class);
        verify(orchestrationService, times(2)).triggerPipelineRun(
                eq(dag.getId()), eq(TriggerType.CRON), optionsCaptor.capture());
        assertThat(optionsCaptor.getAllValues())
                .extracting(OrchestrationService.PipelineRunOptions::logicalDate)
                .containsExactly(expectedLogicalDate, expectedLogicalDate);
        verify(schedulerLockRepo, times(2)).release(anyString(), anyString());
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    void doesNotTriggerWhenSchedulerLockIsHeldByAnotherReplica() {
        when(schedulerLockRepo.acquire(anyString(), anyString(), any())).thenReturn(0);

        service.tickScheduledPipelines(Instant.parse("2026-06-24T10:01:45Z"));

        verifyNoInteractions(dagRepo, orchestrationService);
        verify(schedulerLockRepo, never()).release(anyString(), anyString());
    }

    private Dag scheduledPipeline() {
        Dag dag = new Dag();
        dag.setId(UUID.randomUUID());
        dag.setTenantId(UUID.randomUUID());
        dag.setScheduleCron("0 * * * * *");
        dag.setStatus("PUBLISHED");
        dag.setDagsterJob("onelake_pipeline_run");
        dag.setEnabled(true);
        return dag;
    }
}
