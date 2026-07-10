package com.onelake.orchestration.service;

import com.onelake.orchestration.domain.enums.TriggerType;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable business-time context bound to one pipeline run.
 *
 * <p>{@code logicalDate} always identifies the start of the data interval; it
 * is deliberately distinct from the wall-clock time at which a run is launched.
 * The nullable date fields retain compatibility for legacy trigger paths that
 * do not yet carry a business date.
 */
public record RunContext(
        Instant logicalDate,
        Instant dataIntervalStart,
        Instant dataIntervalEnd,
        String timezone,
        String runMode,
        UUID backfillId,
        TriggerType triggerType
) {

    public static RunContext empty(TriggerType triggerType) {
        return new RunContext(null, null, null, null, null, null, triggerType);
    }

    public RunContext withDefaults(String defaultTimezone, TriggerType defaultTriggerType) {
        return new RunContext(
                logicalDate,
                dataIntervalStart,
                dataIntervalEnd,
                hasText(timezone) ? timezone : defaultTimezone,
                hasText(runMode) ? runMode : "NORMAL",
                backfillId,
                triggerType == null ? defaultTriggerType : triggerType);
    }

    public RunContext withTriggerType(TriggerType value) {
        return new RunContext(
                logicalDate,
                dataIntervalStart,
                dataIntervalEnd,
                timezone,
                runMode,
                backfillId,
                value);
    }

    public RunContext withDataInterval(Instant start, Instant end) {
        return new RunContext(
                logicalDate,
                start,
                end,
                timezone,
                runMode,
                backfillId,
                triggerType);
    }

    /**
     * Keeps the legacy all-null path valid while rejecting partial or
     * contradictory business-time tuples before they reach persistence.
     * A logical-date-only MANUAL context is valid and is completed from the
     * DAG grain by {@link DataIntervalCalculator} at the service boundary.
     */
    public RunContext validateBusinessTime() {
        if (logicalDate == null) {
            if (dataIntervalStart != null || dataIntervalEnd != null) {
                throw new IllegalArgumentException("data_interval 不能脱离 logicalDate 单独设置");
            }
            return this;
        }
        if (dataIntervalStart == null && dataIntervalEnd == null) {
            return this;
        }
        if (dataIntervalStart == null || dataIntervalEnd == null) {
            throw new IllegalArgumentException("dataIntervalStart/dataIntervalEnd 必须同时设置");
        }
        if (!logicalDate.equals(dataIntervalStart)) {
            throw new IllegalArgumentException("logicalDate 必须等于 dataIntervalStart");
        }
        if (!dataIntervalStart.isBefore(dataIntervalEnd)) {
            throw new IllegalArgumentException("dataIntervalEnd 必须晚于 dataIntervalStart");
        }
        return this;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
