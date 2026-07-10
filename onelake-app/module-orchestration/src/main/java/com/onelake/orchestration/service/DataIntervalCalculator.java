package com.onelake.orchestration.service;

import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;

/**
 * 计算调度运行的 Airflow 风格业务数据区间。
 *
 * <p>输入的 scheduledAt 是 cron 命中计划点，也是刚刚变为可运行的数据区间右边界；
 * logicalDate 取上一周期起点，而不是实际启动时间。所有加减周期均在 DAG 时区中进行，
 * 因此日粒度能够正确跨越 DST，月粒度能够正确处理月末。
 */
@Component
public class DataIntervalCalculator {

    /** 从短到长扩大 cron 反向搜索窗口，兼顾常见周期性能和稀疏月度计划。 */
    private static final long[] CRON_LOOKBACK_DAYS = {2, 8, 40, 400};

    /**
     * 根据计划点计算刚结束的业务数据区间。
     *
     * @param cronOrGrain HOUR、DAY、MONTH，或受支持的 Spring 六字段 cron
     * @param scheduledAt cron 命中的计划时刻
     * @param timezone DAG 业务时区
     * @return logicalDate、dataIntervalStart、dataIntervalEnd 三元组
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
        // 显式粒度直接在业务时区向前移动一个自然周期，避免用固定秒数破坏 DST/月末。
        ZonedDateTime intervalEnd = scheduledAt.atZone(zoneId);
        ZonedDateTime intervalStart = previous(intervalEnd, grain);
        Instant start = intervalStart.toInstant();
        return new DataInterval(start, start, intervalEnd.toInstant());
    }

    /**
     * 补齐仅提供 logicalDate 的手动运行上下文。
     *
     * <p>logicalDate 已是区间起点，因此在 DAG 时区中向后推进一个自然周期得到右边界。
     *
     * @param grain HOUR、DAY 或 MONTH
     * @param logicalDate 已指定的业务周期起点
     * @param timezone DAG 业务时区
     * @return 完整业务数据区间
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
        // CronExpression 只提供 next()；逐级扩大向前搜索范围，再正向扫描到基准命中点。
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

    /** 校验 C1 当前支持的小时/日/月 cron 子集，拒绝会产生重叠区间的小时以下计划。 */
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

    /**
     * 统一业务数据区间值对象。
     *
     * @param logicalDate 业务周期标识，等于 dataIntervalStart
     * @param dataIntervalStart 数据区间左边界
     * @param dataIntervalEnd 数据区间右边界
     */
    public record DataInterval(
            Instant logicalDate,
            Instant dataIntervalStart,
            Instant dataIntervalEnd
    ) {
        /** 将区间绑定运行模式、时区和触发来源，生成统一 RunContext。 */
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
