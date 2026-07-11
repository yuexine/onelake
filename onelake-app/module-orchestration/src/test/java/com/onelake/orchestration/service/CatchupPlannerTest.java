package com.onelake.orchestration.service;

import com.onelake.orchestration.domain.entity.Dag;
import com.onelake.orchestration.domain.entity.JobRun;
import com.onelake.orchestration.domain.entity.ScheduleCalendarDay;
import com.onelake.orchestration.domain.entity.ScheduleCalendarDayId;
import com.onelake.orchestration.repository.JobRunRepository;
import com.onelake.orchestration.repository.ScheduleCalendarDayRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CatchupPlannerTest {

    @Mock private JobRunRepository jobRunRepo;
    @Mock private ScheduleCalendarDayRepository calendarDayRepo;

    @Test
    void expandsEveryMissingCronPeriodAfterLastLogicalDate() {
        CatchupPlanner planner = planner();
        Dag dag = dailyDag();
        dag.setCatchup(true);
        JobRun latest = new JobRun();
        latest.setDagId(dag.getId());
        latest.setLogicalDate(Instant.parse("2026-01-01T02:00:00Z"));
        latest.setDataIntervalStart(latest.getLogicalDate());
        latest.setDataIntervalEnd(Instant.parse("2026-01-02T02:00:00Z"));
        when(jobRunRepo.findFirstByDagIdAndLogicalDateIsNotNullAndRunModeNotOrderByLogicalDateDesc(
                dag.getId(), "DEV"))
                .thenReturn(Optional.of(latest));

        CatchupPlanner.CatchupPlan plan = planner.plan(
                dag, Instant.parse("2026-01-05T02:00:00Z"));

        assertThat(plan.windows()).hasSize(2);
        assertThat(plan.windows())
                .extracting(DataIntervalCalculator.DataInterval::logicalDate)
                .containsExactly(
                        Instant.parse("2026-01-02T02:00:00Z"),
                        Instant.parse("2026-01-03T02:00:00Z"));
        assertThat(plan.windows())
                .extracting(DataIntervalCalculator.DataInterval::dataIntervalEnd)
                .containsExactly(
                        Instant.parse("2026-01-03T02:00:00Z"),
                        Instant.parse("2026-01-04T02:00:00Z"));
    }

    @Test
    void catchupDisabledLeavesOnlyCurrentCronPathToScheduler() {
        CatchupPlanner planner = planner();
        Dag dag = dailyDag();
        dag.setCatchup(false);

        assertThat(planner.plan(dag, Instant.parse("2026-01-05T02:00:00Z")).isEmpty()).isTrue();
        verifyNoInteractions(jobRunRepo);
    }

    @Test
    void reenabledDagWithoutRunHistoryPlansFromCreatedAt() {
        CatchupPlanner planner = planner();
        Dag dag = dailyDag();
        dag.setCatchup(true);
        dag.setCreatedAt(Instant.parse("2026-01-01T02:00:00Z"));
        when(jobRunRepo.findFirstByDagIdAndLogicalDateIsNotNullAndRunModeNotOrderByLogicalDateDesc(
                dag.getId(), "DEV"))
                .thenReturn(Optional.empty());

        CatchupPlanner.CatchupPlan plan = planner.plan(
                dag, Instant.parse("2026-01-05T02:00:00Z"));

        assertThat(plan.windows())
                .extracting(DataIntervalCalculator.DataInterval::logicalDate)
                .containsExactly(
                        Instant.parse("2026-01-01T02:00:00Z"),
                        Instant.parse("2026-01-02T02:00:00Z"),
                        Instant.parse("2026-01-03T02:00:00Z"));
    }

    @Test
    void excludesHistoricalHolidayFromCatchupPlan() {
        CatchupPlanner planner = planner();
        Dag dag = dailyDag();
        dag.setCatchup(true);
        UUID calendarId = UUID.randomUUID();
        dag.setCalendarId(calendarId);
        JobRun latest = new JobRun();
        latest.setLogicalDate(Instant.parse("2026-01-01T02:00:00Z"));
        latest.setDataIntervalEnd(Instant.parse("2026-01-02T02:00:00Z"));
        ScheduleCalendarDay holiday = new ScheduleCalendarDay();
        holiday.setCalendarId(calendarId);
        holiday.setDay(java.time.LocalDate.of(2026, 1, 3));
        holiday.setDayType("HOLIDAY");
        when(jobRunRepo.findFirstByDagIdAndLogicalDateIsNotNullAndRunModeNotOrderByLogicalDateDesc(
                dag.getId(), "DEV"))
                .thenReturn(Optional.of(latest));
        when(calendarDayRepo.findById(new ScheduleCalendarDayId(calendarId, holiday.getDay())))
                .thenReturn(Optional.of(holiday));

        CatchupPlanner.CatchupPlan plan = planner.plan(
                dag, Instant.parse("2026-01-05T02:00:00Z"));

        assertThat(plan.windows())
                .extracting(DataIntervalCalculator.DataInterval::logicalDate)
                .containsExactly(Instant.parse("2026-01-03T02:00:00Z"));
    }

    private CatchupPlanner planner() {
        return new CatchupPlanner(jobRunRepo, calendarDayRepo, new DataIntervalCalculator());
    }

    private Dag dailyDag() {
        Dag dag = new Dag();
        dag.setId(UUID.randomUUID());
        dag.setScheduleCron("0 0 2 * * *");
        dag.setTimezone("UTC");
        dag.setPartitionGrain("DAY");
        dag.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return dag;
    }
}
