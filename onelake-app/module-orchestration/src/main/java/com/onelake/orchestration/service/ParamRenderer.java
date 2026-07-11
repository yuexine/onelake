package com.onelake.orchestration.service;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** H1 的确定性键值占位符渲染；H2 时间表达式在此基础上扩展。 */
public final class ParamRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^{}]+)}");

    private ParamRenderer() {
    }

    /**
     * 把文本中的 {@code ${key}} 替换为参数值。
     *
     * <p>未定义占位符保持原样，避免缺参时静默生成错误 SQL；替换使用字面量语义，
     * 参数键和值中的正则字符不会改变渲染行为。</p>
     */
    public static String render(String text, Map<String, String> params) {
        if (text == null || text.isEmpty() || params == null || params.isEmpty()) {
            return text == null ? "" : text;
        }
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuffer rendered = new StringBuffer(text.length());
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = params.get(key);
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(
                    value == null ? matcher.group() : value));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }
}
