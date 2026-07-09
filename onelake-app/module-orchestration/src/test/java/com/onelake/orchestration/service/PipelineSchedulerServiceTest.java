package com.onelake.orchestration.service;

import com.onelake.orchestration.domain.entity.Dag;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PipelineSchedulerService} 的 P6-B cron 评估测试。
 */
class PipelineSchedulerServiceTest {

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
}
