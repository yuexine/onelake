package com.onelake.orchestration.service;

import com.onelake.orchestration.domain.entity.Dag;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P6-B tests for {@link PipelineSchedulerService} cron evaluation.
 */
class PipelineSchedulerServiceTest {

    @Test
    void isCronDueReturnsTrueWhenCronMatchesNowMinute() {
        Dag dag = new Dag();
        dag.setScheduleCron("0 * * * * *");  // every minute at 0 second
        ZonedDateTime prev = ZonedDateTime.of(2026, 6, 24, 10, 0, 0, 0, ZoneId.systemDefault());
        ZonedDateTime now = ZonedDateTime.of(2026, 6, 24, 10, 1, 0, 0, ZoneId.systemDefault());
        assertThat(PipelineSchedulerService.isCronDue(dag, prev, now)).isTrue();
    }

    @Test
    void isCronDueReturnsFalseWhenNoMatchInInterval() {
        Dag dag = new Dag();
        dag.setScheduleCron("0 0 0 1 1 *");  // Jan 1 only
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
        dag.setScheduleCron("0 0 * * * *");  // every hour at minute 0
        // Interval that contains 10:00 → due
        ZonedDateTime prev1 = ZonedDateTime.of(2026, 6, 24, 9, 59, 0, 0, ZoneId.systemDefault());
        ZonedDateTime now1 = ZonedDateTime.of(2026, 6, 24, 10, 0, 0, 0, ZoneId.systemDefault());
        assertThat(PipelineSchedulerService.isCronDue(dag, prev1, now1)).isTrue();
        // Interval that doesn't contain 10:00 → not due
        ZonedDateTime prev2 = ZonedDateTime.of(2026, 6, 24, 10, 15, 0, 0, ZoneId.systemDefault());
        ZonedDateTime now2 = ZonedDateTime.of(2026, 6, 24, 10, 16, 0, 0, ZoneId.systemDefault());
        assertThat(PipelineSchedulerService.isCronDue(dag, prev2, now2)).isFalse();
    }
}
