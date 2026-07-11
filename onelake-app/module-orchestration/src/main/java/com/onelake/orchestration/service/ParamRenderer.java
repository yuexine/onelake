package com.onelake.orchestration.service;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 确定性运行参数渲染器。
 *
 * <p>渲染器只对原始文本中的占位符做一次字面量替换，不执行替换结果中的表达式，也不
 * 拼接或解释任意代码。普通参数来自受控的参数解析结果；时间参数只来自不可变的
 * {@link RunContext}，因此同一 logical date 总能生成同一份 SQL/脚本。</p>
 */
public final class ParamRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^{}]+)}");
    private static final Pattern PARAM_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]*");
    private static final Pattern BIZDATE = Pattern.compile("bizdate([+-]\\d+)?(?::(.+))?");
    private static final Pattern CYCTIME = Pattern.compile("cyctime(?::(.+))?");
    private static final Pattern UPSTREAM = Pattern.compile(
            "upstream\\.([A-Za-z][A-Za-z0-9_-]*)\\.([A-Za-z_][A-Za-z0-9_.-]*)");

    private ParamRenderer() {
    }

    /** 判断参数键是否会被运行时表达式语法占用，避免用户参数保存后被静默覆盖。 */
    public static boolean isReservedExpressionKey(String key) {
        if (key == null) {
            return false;
        }
        return key.startsWith("bizdate")
                || key.startsWith("cyctime")
                || key.startsWith("upstream.");
    }

    /**
     * H1 兼容入口：只渲染普通键值参数。
     */
    public static String render(String text, Map<String, String> params) {
        return render(text, null, params);
    }

    /**
     * 渲染普通参数和动态业务时间参数。
     *
     * <p>{@code bizdate} 取 logical date 在 DAG 时区中的业务日期；偏移按本次运行数据
     * 区间对应的 HOUR/DAY/MONTH 自然粒度计算。{@code cyctime} 取数据区间右边界，即
     * 该周期的计划时刻。未定义的普通参数保持原样，避免静默生成错误 SQL。</p>
     *
     * @throws IllegalArgumentException 表达式、格式、时区或所需运行上下文非法
     */
    public static String render(String text, RunContext context, Map<String, String> params) {
        return render(text, context, params, null);
    }

    /**
     * 渲染普通参数、业务时间参数和同一 JobRun 内的上游节点输出。
     *
     * <p>{@code upstreamOutputs == null} 表示整图 runConfig 构建阶段，此时上游表达式原样
     * 保留，等待 Dagster 节点开始前最终渲染；非 null 时启用严格解析，上游不存在、未成功
     * 或字段缺失都会立即报错。</p>
     */
    public static String render(String text,
                                RunContext context,
                                Map<String, String> params,
                                Map<String, UpstreamTaskOutput> upstreamOutputs) {
        String source = text == null ? "" : text;
        if (source.isEmpty()) {
            return source;
        }
        validate(source);
        Matcher matcher = PLACEHOLDER.matcher(source);
        StringBuffer rendered = new StringBuffer(source.length());
        while (matcher.find()) {
            String expression = matcher.group(1);
            String value = renderExpression(expression, context, params, upstreamOutputs);
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(
                    value == null ? matcher.group() : value));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    /**
     * 只校验占位符语法，供流水线编译阶段提前报告非法表达式。
     */
    public static void validate(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        int cursor = 0;
        while (true) {
            int start = text.indexOf("${", cursor);
            if (start < 0) {
                return;
            }
            int end = text.indexOf('}', start + 2);
            if (end < 0) {
                throw new IllegalArgumentException("参数表达式缺少右花括号，位置: " + start);
            }
            String expression = text.substring(start + 2, end);
            if (expression.isBlank() || expression.indexOf('{') >= 0 || expression.indexOf('$') >= 0) {
                throw invalidExpression(expression, "表达式不能为空或嵌套");
            }
            validateExpression(expression);
            cursor = end + 1;
        }
    }

    /**
     * 提取文本中声明的上游 taskKey，供编译期和节点执行前校验真实图依赖。
     *
     * <p>本方法与渲染使用同一套语法校验，避免图校验和最终渲染对表达式产生不同解释。</p>
     */
    public static Set<String> upstreamTaskKeys(String text) {
        validate(text);
        Set<String> taskKeys = new LinkedHashSet<>();
        Matcher placeholders = PLACEHOLDER.matcher(text == null ? "" : text);
        while (placeholders.find()) {
            Matcher upstream = UPSTREAM.matcher(placeholders.group(1));
            if (upstream.matches()) {
                taskKeys.add(upstream.group(1));
            }
        }
        return taskKeys;
    }

    private static String renderExpression(String expression,
                                           RunContext context,
                                           Map<String, String> params,
                                           Map<String, UpstreamTaskOutput> upstreamOutputs) {
        Matcher upstream = UPSTREAM.matcher(expression);
        if (upstream.matches()) {
            if (upstreamOutputs == null) {
                return null;
            }
            String taskKey = upstream.group(1);
            String fieldPath = upstream.group(2);
            UpstreamTaskOutput taskOutput = upstreamOutputs.get(taskKey);
            if (taskOutput == null) {
                throw invalidExpression(expression,
                        "同一 job_run 内不存在上游节点 " + taskKey);
            }
            if (!taskOutput.succeeded()) {
                throw invalidExpression(expression,
                        "上游节点 " + taskKey + " 尚未成功，当前状态: " + taskOutput.status());
            }
            JsonNode value = taskOutput.outputs();
            for (String field : fieldPath.split("\\.")) {
                value = value == null ? null : value.get(field);
            }
            if (value == null || value.isMissingNode() || value.isNull()) {
                throw invalidExpression(expression,
                        "上游节点 " + taskKey + " 的 outputs 缺少字段 " + fieldPath);
            }
            return value.isValueNode() ? value.asText() : value.toString();
        }

        Matcher bizdate = BIZDATE.matcher(expression);
        if (bizdate.matches()) {
            if (context == null) {
                return params == null ? null : params.get(expression);
            }
            if (context.logicalDate() == null) {
                throw invalidExpression(expression, "bizdate 需要非空 RunContext.logicalDate");
            }
            ZonedDateTime value = context.logicalDate().atZone(resolveZone(context));
            String offset = bizdate.group(1);
            if (offset != null) {
                value = offset(value, parseOffset(offset, expression), resolveGrain(context));
            }
            String pattern = bizdate.group(2);
            return formatter(pattern, DateTimeFormatter.ISO_LOCAL_DATE).format(value);
        }

        Matcher cyctime = CYCTIME.matcher(expression);
        if (cyctime.matches()) {
            if (context == null) {
                return params == null ? null : params.get(expression);
            }
            if (context.dataIntervalEnd() == null) {
                throw invalidExpression(expression, "cyctime 需要非空 RunContext.dataIntervalEnd");
            }
            ZonedDateTime value = context.dataIntervalEnd().atZone(resolveZone(context));
            return formatter(cyctime.group(1), DateTimeFormatter.ISO_OFFSET_DATE_TIME).format(value);
        }
        return params == null ? null : params.get(expression);
    }

    private static void validateExpression(String expression) {
        if (UPSTREAM.matcher(expression).matches()) {
            return;
        }
        if (expression.startsWith("upstream.")) {
            throw invalidExpression(expression,
                    "上游输出表达式仅支持 upstream.<taskKey>.<field>");
        }
        Matcher bizdate = BIZDATE.matcher(expression);
        if (bizdate.matches()) {
            if (bizdate.group(1) != null) {
                parseOffset(bizdate.group(1), expression);
            }
            formatter(bizdate.group(2), DateTimeFormatter.ISO_LOCAL_DATE);
            return;
        }
        Matcher cyctime = CYCTIME.matcher(expression);
        if (cyctime.matches()) {
            formatter(cyctime.group(1), DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            return;
        }
        if (expression.startsWith("bizdate") || expression.startsWith("cyctime")) {
            throw invalidExpression(expression,
                    "时间表达式仅支持 bizdate、bizdate±N、bizdate[:pattern] 或 cyctime[:pattern]");
        }
        if (!PARAM_KEY.matcher(expression).matches()) {
            throw invalidExpression(expression, "参数键只能包含字母、数字、下划线、点和连字符");
        }
    }

    private static long parseOffset(String raw, String expression) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException ex) {
            throw invalidExpression(expression, "时间偏移量超出 long 范围");
        }
    }

    private static DateTimeFormatter formatter(String pattern, DateTimeFormatter defaultFormatter) {
        if (pattern == null) {
            return defaultFormatter;
        }
        if (pattern.isBlank()) {
            throw new IllegalArgumentException("时间格式不能为空");
        }
        try {
            return DateTimeFormatter.ofPattern(pattern, Locale.ROOT);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("时间格式非法: " + pattern + " (" + ex.getMessage() + ")", ex);
        }
    }

    private static ZoneId resolveZone(RunContext context) {
        String timezone = context.timezone();
        try {
            return ZoneId.of(timezone == null || timezone.isBlank() ? "Asia/Shanghai" : timezone);
        } catch (DateTimeException ex) {
            throw new IllegalArgumentException("参数渲染 timezone 非法: " + timezone, ex);
        }
    }

    private static Grain resolveGrain(RunContext context) {
        Instant startInstant = context.dataIntervalStart();
        Instant endInstant = context.dataIntervalEnd();
        if (startInstant == null || endInstant == null || !startInstant.isBefore(endInstant)) {
            throw new IllegalArgumentException("bizdate 偏移需要完整且递增的 dataIntervalStart/dataIntervalEnd");
        }
        ZoneId zone = resolveZone(context);
        ZonedDateTime start = startInstant.atZone(zone);
        if (start.plusHours(1).toInstant().equals(endInstant)) {
            return Grain.HOUR;
        }
        if (start.plusDays(1).toInstant().equals(endInstant)) {
            return Grain.DAY;
        }
        if (start.plusMonths(1).toInstant().equals(endInstant)) {
            return Grain.MONTH;
        }

        // 月末 cron 可能形成 2/28 -> 3/31 这类不能由 plusMonths(1) 精确复现的区间；
        // 只在相邻 YearMonth 且相隔 27~32 个本地自然日时判定为月粒度。
        ZonedDateTime end = endInstant.atZone(zone);
        long days = ChronoUnit.DAYS.between(start.toLocalDate(), end.toLocalDate());
        if (YearMonth.from(start).plusMonths(1).equals(YearMonth.from(end))
                && days >= 27 && days <= 32) {
            return Grain.MONTH;
        }
        throw new IllegalArgumentException(
                "无法从 dataInterval 识别 HOUR/DAY/MONTH 分区粒度: " + startInstant + " -> " + endInstant);
    }

    private static ZonedDateTime offset(ZonedDateTime value, long amount, Grain grain) {
        try {
            return switch (grain) {
                case HOUR -> value.plusHours(amount);
                case DAY -> value.plusDays(amount);
                case MONTH -> value.plusMonths(amount);
            };
        } catch (DateTimeException | ArithmeticException ex) {
            throw new IllegalArgumentException("bizdate 偏移超出支持的时间范围: " + amount, ex);
        }
    }

    private static IllegalArgumentException invalidExpression(String expression, String reason) {
        return new IllegalArgumentException("参数表达式非法 ${" + expression + "}: " + reason);
    }

    private enum Grain {
        HOUR,
        DAY,
        MONTH
    }

    /** 同一 JobRun 内一个节点的状态与 outputs 快照。 */
    public record UpstreamTaskOutput(String status, JsonNode outputs) {
        public boolean succeeded() {
            return "SUCCEEDED".equals(status);
        }
    }
}
