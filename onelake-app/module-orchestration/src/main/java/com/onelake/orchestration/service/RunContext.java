package com.onelake.orchestration.service;

import com.onelake.orchestration.domain.enums.TriggerType;

import java.time.Instant;
import java.util.UUID;

/**
 * 单次流水线运行绑定的不可变业务时间上下文。
 *
 * <p>{@code logicalDate} 始终标识数据区间起点，与实际启动 JobRun 的墙上时间相互独立。
 * CRON、MANUAL 和 BACKFILL 在进入统一运行路径前都归一化为该对象；时间字段允许全空，
 * 以兼容尚未携带业务日期的旧触发路径。
 *
 * @param logicalDate 业务周期标识，非空时必须等于 dataIntervalStart
 * @param dataIntervalStart 数据区间左边界
 * @param dataIntervalEnd 数据区间右边界
 * @param timezone 创建运行时冻结的业务时区
 * @param runMode NORMAL 或 DRY_RUN 等运行模式
 * @param backfillId 来源回填批次；非回填运行为空
 * @param triggerType 运行触发来源
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

    /** 创建不携带业务时间的兼容上下文。 */
    public static RunContext empty(TriggerType triggerType) {
        return new RunContext(null, null, null, null, null, null, triggerType);
    }

    /** 用 DAG 配置补齐缺省时区、运行模式和触发来源，并返回新对象。 */
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

    /** 替换触发来源并返回新对象。 */
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

    /** 为 logical-date-only 上下文补齐数据区间并返回新对象。 */
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
     * 在持久化前校验业务时间元组完整性。
     *
     * <p>全空时间字段仍合法；只有 logicalDate 的 MANUAL 上下文也合法，并由
     * {@link DataIntervalCalculator} 在服务边界按 DAG 粒度补齐。部分区间、左右边界
     * 颠倒或 logicalDate 与区间起点不一致都会被拒绝。
     *
     * @return 校验通过的当前不可变对象，便于链式调用
     * @throws IllegalArgumentException 业务时间字段不完整或相互矛盾
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
