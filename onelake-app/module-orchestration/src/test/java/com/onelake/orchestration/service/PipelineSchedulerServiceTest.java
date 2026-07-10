package com.onelake.orchestration.service;

import com.onelake.common.context.TenantContext;
import com.onelake.orchestration.domain.entity.Dag;
import com.onelake.orchestration.domain.entity.ScheduleCalendarDay;
import com.onelake.orchestration.domain.entity.ScheduleCalendarDayId;
import com.onelake.orchestration.domain.enums.DagStatus;
import com.onelake.orchestration.domain.enums.ScheduleMode;
import com.onelake.orchestration.domain.enums.TriggerType;
import com.onelake.orchestration.repository.DagRepository;
import com.onelake.orchestration.repository.JobRunRepository;
import com.onelake.orchestration.repository.ScheduleCalendarDayRepository;
import com.onelake.orchestration.repository.SchedulerLockRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
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
    @Mock private JobRunRepository jobRunRepo;
    @Mock private ScheduleCalendarDayRepository calendarDayRepo;
    @Mock private CatchupPlanner catchupPlanner;
    @Mock private BackfillDispatcher backfillDispatcher;

    private PipelineSchedulerService service;
    @BeforeEach
    void setup() {
        service = new PipelineSchedulerService(
                dagRepo,
                schedulerLockRepo,
                orchestrationService,
                jobRunRepo,
                calendarDayRepo,
                catchupPlanner,
                backfillDispatcher);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void isCronDueReturnsTrueWhenCronMatchesNowMinute() {
        Dag dag = new Dag();
        dag.setScheduleCron("0 * * * * *");  // 每分钟第 0 秒触发。
        dag.setTimezone("UTC");
        ZonedDateTime prev = ZonedDateTime.of(2026, 6, 24, 10, 0, 0, 0, ZoneId.of("UTC"));
        ZonedDateTime now = ZonedDateTime.of(2026, 6, 24, 10, 1, 0, 0, ZoneId.of("UTC"));
        assertThat(PipelineSchedulerService.isCronDue(dag, prev, now)).isTrue();
    }

    @Test
    void isCronDueReturnsFalseWhenNoMatchInInterval() {
        Dag dag = new Dag();
        dag.setScheduleCron("0 0 0 1 1 *");  // 仅每年 1 月 1 日触发。
        dag.setTimezone("UTC");
        ZonedDateTime prev = ZonedDateTime.of(2026, 6, 24, 10, 0, 0, 0, ZoneId.of("UTC"));
        ZonedDateTime now = ZonedDateTime.of(2026, 6, 24, 10, 1, 0, 0, ZoneId.of("UTC"));
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
        dag.setTimezone("UTC");
        // 区间包含 10:00，应该到期。
        ZonedDateTime prev1 = ZonedDateTime.of(2026, 6, 24, 9, 59, 0, 0, ZoneId.of("UTC"));
        ZonedDateTime now1 = ZonedDateTime.of(2026, 6, 24, 10, 0, 0, 0, ZoneId.of("UTC"));
        assertThat(PipelineSchedulerService.isCronDue(dag, prev1, now1)).isTrue();
        // 区间不包含 10:00，不应该到期。
        ZonedDateTime prev2 = ZonedDateTime.of(2026, 6, 24, 10, 15, 0, 0, ZoneId.of("UTC"));
        ZonedDateTime now2 = ZonedDateTime.of(2026, 6, 24, 10, 16, 0, 0, ZoneId.of("UTC"));
        assertThat(PipelineSchedulerService.isCronDue(dag, prev2, now2)).isFalse();
    }

    @Test
    void isCronDueEvaluatesInDagTimezone() {
        Dag newYorkDag = new Dag();
        newYorkDag.setTimezone("America/New_York");
        newYorkDag.setScheduleCron("0 0 9 * * *");
        Instant previous = Instant.parse("2026-06-24T12:59:00Z");
        Instant now = Instant.parse("2026-06-24T13:00:00Z");

        assertThat(PipelineSchedulerService.isCronDue(newYorkDag, previous, now)).isTrue();

        Dag utcDag = new Dag();
        utcDag.setTimezone("UTC");
        utcDag.setScheduleCron("0 0 9 * * *");
        assertThat(PipelineSchedulerService.isCronDue(utcDag, previous, now)).isFalse();
    }

    @Test
    void holidayCalendarSkipsDuePipeline() {
        Dag dag = scheduledPipeline();
        UUID calendarId = UUID.randomUUID();
        dag.setCalendarId(calendarId);
        ScheduleCalendarDay holiday = new ScheduleCalendarDay();
        holiday.setCalendarId(calendarId);
        holiday.setDay(java.time.LocalDate.of(2026, 6, 24));
        holiday.setDayType("HOLIDAY");
        stubLockedTick(List.of(dag));
        when(calendarDayRepo.findById(new ScheduleCalendarDayId(calendarId, holiday.getDay())))
                .thenReturn(Optional.of(holiday));

        service.tickScheduledPipelines(Instant.parse("2026-06-24T10:01:45Z"));

        verifyNoInteractions(orchestrationService);
        verify(jobRunRepo, never()).countByDagIdAndStatusIn(any(), any());
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    void frozenPipelineDoesNotTriggerAndBlocksDependencyReadiness() {
        Dag dag = scheduledPipeline();
        dag.setScheduleMode("FROZEN");
        stubLockedTick(List.of(dag));

        service.tickScheduledPipelines(Instant.parse("2026-06-24T10:01:45Z"));

        verifyNoInteractions(orchestrationService);
        verify(jobRunRepo, never()).countByDagIdAndStatusIn(any(), any());
        assertThat(ScheduleMode.from(dag.getScheduleMode()).satisfiesDependency(DagStatus.SUCCEEDED))
                .isFalse();
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    void maxActiveRunsRecordsMisfireWithoutTriggering() {
        Dag dag = scheduledPipeline();
        dag.setMaxActiveRuns(1);
        stubLockedTick(List.of(dag));
        when(jobRunRepo.countByDagIdAndStatusIn(eq(dag.getId()), any())).thenReturn(1L);

        service.tickScheduledPipelines(Instant.parse("2026-06-24T10:01:45Z"));

        verifyNoInteractions(orchestrationService);
        verifyNoInteractions(catchupPlanner, backfillDispatcher);
    }

    @Test
    void duePipelinesTriggerByDescendingPriority() {
        Dag low = scheduledPipeline();
        low.setPriority(1);
        Dag high = scheduledPipeline();
        high.setPriority(10);
        stubLockedTick(List.of(low, high));

        service.tickScheduledPipelines(Instant.parse("2026-06-24T10:01:45Z"));

        InOrder order = inOrder(orchestrationService);
        order.verify(orchestrationService).triggerPipelineRun(
                eq(high.getId()), eq(TriggerType.CRON), any(Instant.class));
        order.verify(orchestrationService).triggerPipelineRun(
                eq(low.getId()), eq(TriggerType.CRON), any(Instant.class));
    }

    @Test
    void submitsCatchupAfterCurrentCronRun() {
        Dag dag = scheduledPipeline();
        dag.setCatchup(true);
        dag.setMaxActiveRuns(2);
        Instant tickAt = Instant.parse("2026-06-24T10:01:45Z");
        Instant scheduledAt = Instant.parse("2026-06-24T10:01:00Z");
        CatchupPlanner.CatchupPlan plan = new CatchupPlanner.CatchupPlan(List.of(
                new DataIntervalCalculator.DataInterval(
                        Instant.parse("2026-06-22T10:01:00Z"),
                        Instant.parse("2026-06-22T10:01:00Z"),
                        Instant.parse("2026-06-23T10:01:00Z"))));
        stubLockedTick(List.of(dag));
        when(catchupPlanner.plan(dag, scheduledAt)).thenReturn(plan);

        service.tickScheduledPipelines(tickAt);

        InOrder order = inOrder(orchestrationService, backfillDispatcher);
        order.verify(orchestrationService).triggerPipelineRun(
                dag.getId(), TriggerType.CRON, scheduledAt);
        order.verify(backfillDispatcher).dispatchCatchup(
                dag.getId(), plan.windows(), dag.getPartitionGrain(), 2);
    }

    @Test
    void sameLogicalDateSecondTriggerIsDeduplicated() {
        Dag dag = scheduledPipeline();
        Instant tickAt = Instant.parse("2026-06-24T10:01:45Z");
        Instant scheduledAt = Instant.parse("2026-06-24T10:01:00Z");
        when(schedulerLockRepo.acquire(anyString(), anyString(), any())).thenReturn(1);
        when(schedulerLockRepo.release(anyString(), anyString())).thenReturn(1);
        when(dagRepo.findByEnabledTrue()).thenReturn(List.of(dag));
        when(orchestrationService.triggerPipelineRun(eq(dag.getId()), eq(TriggerType.CRON), any(Instant.class)))
                .thenReturn(UUID.randomUUID())
                .thenThrow(new DataIntegrityViolationException("uq_job_run_cron_logical"));

        assertThatCode(() -> {
            service.tickScheduledPipelines(tickAt);
            service.tickScheduledPipelines(tickAt);
        }).doesNotThrowAnyException();

        ArgumentCaptor<Instant> scheduledAtCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(orchestrationService, times(2)).triggerPipelineRun(
                eq(dag.getId()), eq(TriggerType.CRON), scheduledAtCaptor.capture());
        assertThat(scheduledAtCaptor.getAllValues()).containsExactly(scheduledAt, scheduledAt);
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

    private void stubLockedTick(List<Dag> dags) {
        when(schedulerLockRepo.acquire(anyString(), anyString(), any())).thenReturn(1);
        when(schedulerLockRepo.release(anyString(), anyString())).thenReturn(1);
        when(dagRepo.findByEnabledTrue()).thenReturn(dags);
    }

    private Dag scheduledPipeline() {
        Dag dag = new Dag();
        dag.setId(UUID.randomUUID());
        dag.setTenantId(UUID.randomUUID());
        dag.setScheduleCron("0 * * * * *");
        dag.setTimezone("UTC");
        dag.setStatus("PUBLISHED");
        dag.setDagsterJob("onelake_pipeline_run");
        dag.setEnabled(true);
        return dag;
    }
}
