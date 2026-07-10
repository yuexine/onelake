package com.onelake.orchestration.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataIntervalCalculatorTest {

    private final DataIntervalCalculator calculator = new DataIntervalCalculator();

    @Test
    void calculatesDailyIntervalFromThePreviousScheduledPeriod() {
        DataIntervalCalculator.DataInterval interval = calculator.calculate(
                "DAY",
                Instant.parse("2026-04-11T02:00:00Z"),
                "UTC");

        assertThat(interval.logicalDate()).isEqualTo(Instant.parse("2026-04-10T02:00:00Z"));
        assertThat(interval.dataIntervalStart()).isEqualTo(Instant.parse("2026-04-10T02:00:00Z"));
        assertThat(interval.dataIntervalEnd()).isEqualTo(Instant.parse("2026-04-11T02:00:00Z"));
    }

    @Test
    void infersHourlyIntervalFromCron() {
        DataIntervalCalculator.DataInterval interval = calculator.calculate(
                "0 0 * * * *",
                Instant.parse("2026-04-11T10:00:00Z"),
                "UTC");

        assertThat(interval.logicalDate()).isEqualTo(Instant.parse("2026-04-11T09:00:00Z"));
        assertThat(interval.dataIntervalEnd()).isEqualTo(Instant.parse("2026-04-11T10:00:00Z"));
    }

    @Test
    void usesThePreviousActualCronHitForSteppedHours() {
        DataIntervalCalculator.DataInterval interval = calculator.calculate(
                "0 0 */2 * * *",
                Instant.parse("2026-04-11T10:00:00Z"),
                "UTC");

        assertThat(interval.logicalDate()).isEqualTo(Instant.parse("2026-04-11T08:00:00Z"));
        assertThat(interval.dataIntervalEnd()).isEqualTo(Instant.parse("2026-04-11T10:00:00Z"));
    }

    @Test
    void usesThePreviousExistingCronHitAcrossMissingMonthEnd() {
        DataIntervalCalculator.DataInterval interval = calculator.calculate(
                "0 0 2 31 * *",
                Instant.parse("2026-03-31T02:00:00Z"),
                "UTC");

        assertThat(interval.logicalDate()).isEqualTo(Instant.parse("2026-01-31T02:00:00Z"));
        assertThat(interval.dataIntervalEnd()).isEqualTo(Instant.parse("2026-03-31T02:00:00Z"));
    }

    @Test
    void rejectsSubHourlyCronInsteadOfCreatingOverlappingIntervals() {
        assertThatThrownBy(() -> calculator.calculate(
                "0 */15 * * * *",
                Instant.parse("2026-04-11T10:00:00Z"),
                "UTC"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("小时以下粒度");
    }

    @Test
    void calculatesMonthlyIntervalUsingCalendarMonths() {
        DataIntervalCalculator.DataInterval interval = calculator.calculate(
                "MONTH",
                Instant.parse("2026-03-01T00:00:00Z"),
                "UTC");

        assertThat(interval.logicalDate()).isEqualTo(Instant.parse("2026-02-01T00:00:00Z"));
        assertThat(interval.dataIntervalEnd()).isEqualTo(Instant.parse("2026-03-01T00:00:00Z"));
    }

    @Test
    void preservesTheDagTimezoneAcrossDaylightSavingTransition() {
        DataIntervalCalculator.DataInterval interval = calculator.calculate(
                "DAY",
                Instant.parse("2026-03-09T04:00:00Z"),
                "America/New_York");

        // 2026-03-08 is the DST changeover: the local daily period has 23 hours.
        assertThat(interval.logicalDate()).isEqualTo(Instant.parse("2026-03-08T05:00:00Z"));
        assertThat(interval.dataIntervalStart()).isEqualTo(Instant.parse("2026-03-08T05:00:00Z"));
        assertThat(interval.dataIntervalEnd()).isEqualTo(Instant.parse("2026-03-09T04:00:00Z"));
    }

    @Test
    void handlesMonthEndBoundaryInLeapYear() {
        DataIntervalCalculator.DataInterval interval = calculator.calculate(
                "MONTH",
                Instant.parse("2024-03-31T00:00:00Z"),
                "UTC");

        assertThat(interval.logicalDate()).isEqualTo(Instant.parse("2024-02-29T00:00:00Z"));
        assertThat(interval.dataIntervalEnd()).isEqualTo(Instant.parse("2024-03-31T00:00:00Z"));
    }

    @Test
    void completesLogicalDateAcrossDaylightSavingTransition() {
        DataIntervalCalculator.DataInterval interval = calculator.calculateFromLogicalDate(
                "DAY",
                Instant.parse("2026-03-08T05:00:00Z"),
                "America/New_York");

        assertThat(interval.dataIntervalEnd()).isEqualTo(Instant.parse("2026-03-09T04:00:00Z"));
    }
}
