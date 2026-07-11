package com.onelake.orchestration.service;

import com.onelake.orchestration.domain.enums.TriggerType;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
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

    /** 用户参数不得占用这些运行时保留键。 */
    public static final Set<String> RESERVED_PARAM_KEYS = Set.of(
            "run_id",
            "logical_date",
            "bizdate",
            "data_interval_start",
            "data_interval_end",
            "timezone",
            "run_mode",
            "backfill_id",
            "trigger_type");

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

    /** 替换运行模式并返回新对象。 */
    public RunContext withRunMode(String value) {
        return new RunContext(
                logicalDate,
                dataIntervalStart,
                dataIntervalEnd,
                timezone,
                value,
                backfillId,
                triggerType);
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
     * 生成本次运行的内置参数。内置键是保留键，合并时始终优先于用户参数。
     * 时间为空的兼容触发路径不会伪造 logical date/data interval 参数。
     */
    public Map<String, String> builtInParameters(UUID runId) {
        Map<String, String> params = new LinkedHashMap<>();
        put(params, "run_id", runId);
        put(params, "logical_date", logicalDate);
        if (logicalDate != null) {
            params.put("bizdate", DateTimeFormatter.ISO_LOCAL_DATE.format(
                    logicalDate.atZone(resolveTimezone(timezone)).toLocalDate()));
        }
        put(params, "data_interval_start", dataIntervalStart);
        put(params, "data_interval_end", dataIntervalEnd);
        put(params, "timezone", timezone);
        put(params, "run_mode", runMode);
        put(params, "backfill_id", backfillId);
        put(params, "trigger_type", triggerType == null ? null : triggerType.name());
        return Collections.unmodifiableMap(params);
    }

    /**
     * 合并用户参数和当前运行内置参数。所有保留键先从用户字典移除，再写入真实内置值，
     * 因而即使某个内置值在兼容上下文中为空，也不能由用户伪造。
     */
    public Map<String, String> finalParameters(UUID runId, Map<String, String> userParameters) {
        return mergeWithBuiltIns(userParameters, builtInParameters(runId));
    }

    /** 供 runConfig 构建器把逐节点用户参数与已生成的内置参数合并。 */
    public static Map<String, String> mergeWithBuiltIns(
            Map<String, String> userParameters,
            Map<String, String> builtInParameters) {
        Map<String, String> merged = new LinkedHashMap<>();
        if (userParameters != null) {
            userParameters.forEach((key, value) -> {
                if (key != null && !key.isBlank() && value != null && !RESERVED_PARAM_KEYS.contains(key)) {
                    merged.put(key, value);
                }
            });
        }
        if (builtInParameters != null) {
            builtInParameters.forEach((key, value) -> {
                if (key != null && !key.isBlank() && value != null) {
                    merged.put(key, value);
                }
            });
        }
        return merged.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(merged);
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

    private static void put(Map<String, String> target, String key, Object value) {
        if (value != null) {
            target.put(key, value.toString());
        }
    }

    private static ZoneId resolveTimezone(String value) {
        try {
            return ZoneId.of(hasText(value) ? value : "Asia/Shanghai");
        } catch (RuntimeException ignored) {
            return ZoneId.of("Asia/Shanghai");
        }
    }
}
