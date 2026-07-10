package com.onelake.orchestration.service;

import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;

/**
 * Computes Airflow-style data intervals for scheduled pipeline runs.
 *
 * <p>The supplied base instant is the cron hit time, i.e. the end of the
 * interval that has just become eligible to run. Consequently the logical
 * date is the previous interval start, never the actual launch time.
 */
@Component
public class DataIntervalCalculator {

    private static final long[] CRON_LOOKBACK_DAYS = {2, 8, 40, 400};

    /**
     * Accepts either a supported grain ({@code HOUR}, {@code DAY},
     * {@code MONTH}) or a six-field Spring cron expression.
     */
    public DataInterval calculate(String cronOrGrain, Instant scheduledAt, String timezone) {
        if (scheduledAt == null) {
            throw new IllegalArgumentException("scheduledAt 不能为空");
        }
        ZoneId zoneId = resolveZone(timezone);
        Grain grain = resolveExplicitGrain(cronOrGrain);
        if (grain == null) {
            return calculateCron(cronOrGrain, scheduledAt.atZone(zoneId));
        }
        ZonedDateTime intervalEnd = scheduledAt.atZone(zoneId);
        ZonedDateTime intervalStart = previous(intervalEnd, grain);
        Instant start = intervalStart.toInstant();
        return new DataInterval(start, start, intervalEnd.toInstant());
    }

    /**
     * Completes a MANUAL context that only supplied its logical date. The
     * logical date is already the interval start, so the end is advanced in
     * the DAG timezone to preserve DST and month-end semantics.
     */
    public DataInterval calculateFromLogicalDate(String grain, Instant logicalDate, String timezone) {
        if (logicalDate == null) {
            throw new IllegalArgumentException("logicalDate 不能为空");
        }
        Grain resolvedGrain = resolveExplicitGrain(grain);
        if (resolvedGrain == null) {
            throw new IllegalArgumentException("logicalDate 补全仅支持 HOUR/DAY/MONTH 粒度: " + grain);
        }
        ZonedDateTime intervalStart = logicalDate.atZone(resolveZone(timezone));
        ZonedDateTime intervalEnd = next(intervalStart, resolvedGrain);
        return new DataInterval(logicalDate, logicalDate, intervalEnd.toInstant());
    }

    private ZoneId resolveZone(String timezone) {
        try {
            return ZoneId.of(timezone == null || timezone.isBlank() ? "Asia/Shanghai" : timezone);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("timezone 非法: " + timezone, ex);
        }
    }

    private Grain resolveExplicitGrain(String cronOrGrain) {
        if (cronOrGrain == null || cronOrGrain.isBlank()) {
            return Grain.DAY;
        }
        String value = cronOrGrain.trim().toUpperCase(Locale.ROOT);
        try {
            return Grain.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private DataInterval calculateCron(String cron, ZonedDateTime intervalEnd) {
        validateCronShape(cron);
        CronExpression expression;
        try {
            expression = CronExpression.parse(cron);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("cron 非法: " + cron, ex);
        }
        ZonedDateTime intervalStart = previousCronHit(expression, intervalEnd, cron);
        Instant start = intervalStart.toInstant();
        return new DataInterval(start, start, intervalEnd.toInstant());
    }

    private ZonedDateTime previousCronHit(CronExpression expression,
                                          ZonedDateTime intervalEnd,
                                          String cron) {
        for (long lookbackDays : CRON_LOOKBACK_DAYS) {
            ZonedDateTime previous = scanPreviousCronHit(
                    expression,
                    intervalEnd.minusDays(lookbackDays).minusNanos(1),
                    intervalEnd,
                    cron);
            if (previous != null) {
                return previous;
            }
        }
        throw new IllegalArgumentException("无法在支持范围内找到上一 cron 计划点: " + cron);
    }

    private ZonedDateTime scanPreviousCronHit(CronExpression expression,
                                              ZonedDateTime searchStart,
                                              ZonedDateTime intervalEnd,
                                              String cron) {
        ZonedDateTime cursor = searchStart;
        ZonedDateTime previous = null;
        for (int i = 0; i < 100_000; i++) {
            ZonedDateTime candidate = expression.next(cursor);
            if (candidate == null || candidate.isAfter(intervalEnd)) {
                throw new IllegalArgumentException("基准时刻不是 cron 命中时间: " + cron);
            }
            if (candidate.isEqual(intervalEnd)) {
                return previous;
            }
            previous = candidate;
            cursor = candidate;
        }
        throw new IllegalArgumentException("cron 计划点过密，超出 C1 支持范围: " + cron);
    }

    private ZonedDateTime previous(ZonedDateTime intervalEnd, Grain grain) {
        return switch (grain) {
            case HOUR -> intervalEnd.minusHours(1);
            case DAY -> intervalEnd.minusDays(1);
            case MONTH -> intervalEnd.minusMonths(1);
        };
    }

    private ZonedDateTime next(ZonedDateTime intervalStart, Grain grain) {
        return switch (grain) {
            case HOUR -> intervalStart.plusHours(1);
            case DAY -> intervalStart.plusDays(1);
            case MONTH -> intervalStart.plusMonths(1);
        };
    }

    /** Validates the hourly/daily/monthly cron subset supported by C1. */
    private void validateCronShape(String cron) {
        String[] fields = cron.trim().split("\\s+");
        if (fields.length != 6) {
            throw new IllegalArgumentException("仅支持六字段 cron 或 HOUR/DAY/MONTH 粒度: " + cron);
        }
        if (!isSingleNumber(fields[0]) || !isSingleNumber(fields[1])) {
            throw new IllegalArgumentException("C1 不支持小时以下粒度 cron: " + cron);
        }
        String month = fields[4];
        if (!isWildcard(month)) {
            throw new IllegalArgumentException("C1 仅支持小时、日、月粒度 cron: " + cron);
        }
    }

    private boolean isWildcard(String field) {
        return "*".equals(field) || "?".equals(field);
    }

    private boolean isSingleNumber(String field) {
        return field != null && field.matches("\\d+");
    }

    private enum Grain {
        HOUR,
        DAY,
        MONTH
    }

    public record DataInterval(
            Instant logicalDate,
            Instant dataIntervalStart,
            Instant dataIntervalEnd
    ) {
        public RunContext toRunContext(String timezone,
                                       String runMode,
                                       java.util.UUID backfillId,
                                       com.onelake.orchestration.domain.enums.TriggerType triggerType) {
            return new RunContext(
                    logicalDate,
                    dataIntervalStart,
                    dataIntervalEnd,
                    timezone,
                    runMode,
                    backfillId,
                    triggerType);
        }
    }
}
