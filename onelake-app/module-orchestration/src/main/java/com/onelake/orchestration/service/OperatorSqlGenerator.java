package com.onelake.orchestration.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.onelake.orchestration.dto.OperatorManifestDTO;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将算子 Manifest 的受控模板渲染为 Spark SQL 片段。
 *
 * <p>模板只支持白名单内的 {@code {{ parameter }}}、{@code join} 过滤器和内置宏。
 * 普通参数按 SQL 角色分别编码为标识符、字面量、数值、类型或受控表达式；未知参数
 * 默认按字面量处理，不能静默升级为可执行 SQL。最终替换复用 {@link ParamRenderer}
 * 的单次渲染语义，替换值中的占位符不会被递归执行。</p>
 */
@Service
public class OperatorSqlGenerator {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*(.+?)\\s*}}", Pattern.DOTALL);
    private static final Pattern SIMPLE_PARAMETER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern JOIN_FILTER = Pattern.compile(
            "([A-Za-z_][A-Za-z0-9_]*)\\s*\\|\\s*join\\(\\s*(['\"])(,\\s*)\\2\\s*\\)");
    private static final Pattern SOURCE_MACRO = Pattern.compile(
            "source\\(\\s*'([a-z][a-z0-9_]*)'\\s*,\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\)");
    private static final Pattern DWD_MACRO = Pattern.compile(
            "dwd\\(\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\)");
    private static final Pattern MASK_ID_CARD_MACRO = Pattern.compile(
            "mask_id_card\\(\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\)");
    private static final Pattern NUMBER = Pattern.compile("[+-]?(?:\\d+(?:\\.\\d+)?|\\.\\d+)");
    private static final Pattern SQL_TYPE = Pattern.compile(
            "(?i)(?:BOOLEAN|TINYINT|SMALLINT|INT|INTEGER|BIGINT|FLOAT|DOUBLE|REAL|STRING|"
                    + "BINARY|DATE|TIMESTAMP|TIMESTAMP_NTZ|"
                    + "DECIMAL(?:\\(\\d{1,3}(?:,\\d{1,3})?\\))?|"
                    + "VARCHAR(?:\\(\\d{1,5}\\))?|CHAR(?:\\(\\d{1,5}\\))?)");
    private static final Pattern QUERY_PREFIX = Pattern.compile("(?is)^(?:SELECT|WITH)\\b.*");
    private static final Pattern STATEMENT_PREFIX = Pattern.compile(
            "(?is)^(?:SELECT|WITH|CREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:TABLE|VIEW)|"
                    + "INSERT\\s+INTO|MERGE\\s+INTO)\\b.*");
    private static final Pattern EXPRESSION_FORBIDDEN = Pattern.compile(
            "(?i)\\b(?:INSERT|UPDATE|DELETE|DROP|ALTER|CREATE|GRANT|REVOKE|TRUNCATE|USE|"
                    + "SET|CALL|EXECUTE|MERGE|LOAD|REFRESH|CACHE|UNCACHE|SELECT|WITH|FROM|JOIN|"
                    + "UNION|INTERSECT|EXCEPT)\\b");
    private static final Pattern FUNCTION_CALL = Pattern.compile(
            "(?i)\\b([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");
    private static final Set<String> FUNCTION_WHITELIST = Set.of(
            "lower", "upper", "initcap", "trim", "ltrim", "rtrim");
    /** Spark 可借此调用 JVM 方法，不能由任何算子模板或节点参数引入。 */
    private static final Set<String> FORBIDDEN_SQL_FUNCTIONS = Set.of(
            "reflect", "java_method");
    private static final Set<String> EXPRESSION_FUNCTION_WHITELIST = Set.of(
            "abs", "aes_encrypt", "array", "array_contains", "avg", "cast", "ceil", "ceiling",
            "coalesce", "concat", "concat_ws", "count", "date_add", "date_format", "date_parse",
            "date_sub", "dense_rank", "element_at", "floor", "greatest", "hex", "if", "in",
            "initcap", "least", "length", "lower", "ltrim", "max", "md5", "min", "nullif",
            "over", "rank", "regexp_extract", "regexp_replace", "repeat", "round", "row_number",
            "sha2", "sha256", "split", "split_part", "substr", "substring", "sum", "to_date",
            "to_hex", "to_timestamp", "tokenize", "trim", "unhex", "unnest", "upper",
            "fpe_encrypt");

    private static final Set<String> IDENTIFIER_PARAMETERS = Set.of(
            "sourceFqn", "modelRef", "targetFqn", "column", "name", "as", "uniqueKey",
            "incrementalColumn", "refModel", "refColumn", "dimRef", "tokenTable", "standardId");
    private static final Set<String> IDENTIFIER_LIST_PARAMETERS = Set.of(
            "columns", "keys", "groupBy", "partitionBy", "orderBy", "order", "requiredColumns");
    private static final Set<String> LITERAL_PARAMETERS = Set.of(
            "value", "fillValue", "sep", "delimiter", "else", "pattern", "replacement",
            "inputFmt", "salt", "keyRef", "fromUnit", "toUnit", "maxDelay");
    private static final Set<String> NUMBER_PARAMETERS = Set.of(
            "min", "max", "factor", "keepHead", "keepTail");
    private static final Set<String> TYPE_PARAMETERS = Set.of(
            "type", "targetType", "outputType");
    private static final Set<String> EXPRESSION_PARAMETERS = Set.of(
            "expr", "predicate", "fn", "dictType", "buckets", "select", "assertionSql");
    private static final Set<String> EXPRESSION_LIST_PARAMETERS = Set.of(
            "cases", "aggregations", "enrichColumns");

    /** G1 支持的算子模板类型。 */
    public enum TemplateKind {
        SELECT_EXPR,
        COLUMN_EXPR,
        FILTER,
        SPARK_SQL,
        SPARK_SINK,
        RAW_SQL
    }

    /** 用节点 config 渲染 Manifest 模板，返回尚未与上下游表组合的安全 SQL 片段。 */
    public String generate(OperatorManifestDTO manifest, JsonNode config) {
        if (manifest == null) {
            throw new IllegalArgumentException("operator manifest must not be null");
        }
        if (config == null || !config.isObject()) {
            throw new IllegalArgumentException("operator config must be a JSON object");
        }
        TemplateKind kind = templateKind(manifest);
        String template = templateSql(manifest);
        validateRequiredConfig(manifest, config);
        validateTemplateDelimiters(template);
        validateStaticTemplate(kind, template);

        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder parameterized = new StringBuilder(template.length());
        Map<String, String> values = new LinkedHashMap<>();
        int cursor = 0;
        int index = 0;
        while (matcher.find()) {
            parameterized.append(template, cursor, matcher.start());
            String key = "operator_token_" + index++;
            values.put(key, renderToken(matcher.group(1).trim(), manifest, config, kind));
            parameterized.append("${").append(key).append('}');
            cursor = matcher.end();
        }
        parameterized.append(template, cursor, template.length());

        String rendered = ParamRenderer.render(parameterized.toString(), values).trim();
        if (!StringUtils.hasText(rendered)) {
            throw new IllegalArgumentException("operator template rendered empty SQL");
        }
        validateRenderedSql(kind, rendered);
        return rendered;
    }

    /** 从 Manifest 读取并校验 G1 模板类型。 */
    public TemplateKind templateKind(OperatorManifestDTO manifest) {
        if (manifest == null || manifest.template() == null) {
            throw new IllegalArgumentException("operator manifest.template must not be null");
        }
        Object raw = manifest.template().get("kind");
        if (raw == null || !StringUtils.hasText(String.valueOf(raw))) {
            raw = manifest.template().get("templateKind");
        }
        if (raw == null || !StringUtils.hasText(String.valueOf(raw))) {
            throw new IllegalArgumentException("operator manifest.template.kind must not be empty");
        }
        try {
            return TemplateKind.valueOf(String.valueOf(raw).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "unsupported operator template kind: " + raw, ex);
        }
    }

    /** Spark SQL 标识符编码；点分 FQN 的每一段分别编码。 */
    public static String quoteQualifiedIdentifier(String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("SQL identifier must not be empty");
        }
        if (value.chars().anyMatch(ch -> Character.isISOControl(ch))) {
            throw new IllegalArgumentException("SQL identifier must not contain control characters");
        }
        String[] parts = value.trim().split("\\.", -1);
        List<String> quoted = new ArrayList<>(parts.length);
        for (String part : parts) {
            if (part.isEmpty()) {
                throw new IllegalArgumentException("SQL identifier contains an empty segment: " + value);
            }
            quoted.add(quoteIdentifier(part));
        }
        return String.join(".", quoted);
    }

    /** Spark SQL 单段标识符编码。 */
    public static String quoteIdentifier(String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("SQL identifier must not be empty");
        }
        if (value.chars().anyMatch(ch -> Character.isISOControl(ch))) {
            throw new IllegalArgumentException("SQL identifier must not contain control characters");
        }
        return "`" + value.trim().replace("`", "``") + "`";
    }

    /** SQL 字面量编码；供编译器组合列变换等完整语句时复用。 */
    public static String quoteLiteral(String value) {
        if (value == null) {
            return "NULL";
        }
        // Spark SQL 3.5 在默认 escapedStringLiterals=false 下不会把 ANSI 风格的
        // 两个单引号还原为一个单引号，而是按相邻字符串片段解析并吞掉引号。
        // 先编码反斜杠、再编码单引号，既能保持原值，也避免用户提供的反斜杠
        // 抵消我们为单引号添加的转义。
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    /** 判断单条受控 SQL 是否为查询。 */
    public static boolean isQuery(String sql) {
        return QUERY_PREFIX.matcher(stripOuterParentheses(sql)).matches();
    }

    /** 去掉 RAW_SQL 模板常见的一层外括号。 */
    public static String stripOuterParentheses(String sql) {
        String value = sql == null ? "" : sql.trim();
        if (value.length() >= 2 && value.charAt(0) == '(' && value.charAt(value.length() - 1) == ')') {
            return value.substring(1, value.length() - 1).trim();
        }
        return value;
    }

    private String templateSql(OperatorManifestDTO manifest) {
        Object raw = manifest.template().get("sql");
        if (raw == null || !StringUtils.hasText(String.valueOf(raw))) {
            throw new IllegalArgumentException("operator manifest.template.sql must not be empty");
        }
        return String.valueOf(raw);
    }

    private String renderToken(String expression,
                               OperatorManifestDTO manifest,
                               JsonNode config,
                               TemplateKind kind) {
        Matcher join = JOIN_FILTER.matcher(expression);
        if (join.matches()) {
            return renderIdentifierList(requiredConfig(config, join.group(1)), join.group(1));
        }
        Matcher source = SOURCE_MACRO.matcher(expression);
        if (source.matches()) {
            String value = requiredConfigText(config, source.group(2));
            String qualified = value.contains(".") ? value : source.group(1) + "." + value;
            return quoteQualifiedIdentifier(qualified);
        }
        Matcher dwd = DWD_MACRO.matcher(expression);
        if (dwd.matches()) {
            String value = requiredConfigText(config, dwd.group(1));
            return quoteQualifiedIdentifier(value.contains(".") ? value : "dwd." + value);
        }
        Matcher maskIdCard = MASK_ID_CARD_MACRO.matcher(expression);
        if (maskIdCard.matches()) {
            String column = quoteQualifiedIdentifier(requiredConfigText(config, maskIdCard.group(1)));
            return "concat(substr(" + column + ", 1, 3), '***********', substr(" + column + ", -4))";
        }
        if (!SIMPLE_PARAMETER.matcher(expression).matches()) {
            throw new IllegalArgumentException("operator template expression is not whitelisted: {{ "
                    + expression + " }}");
        }
        JsonNode value = requiredConfig(config, expression);
        SqlRole role = configuredRole(manifest, expression);
        if (role == null) {
            role = defaultRole(expression, kind);
        }
        return renderValue(value, expression, role);
    }

    private SqlRole configuredRole(OperatorManifestDTO manifest, String parameter) {
        if (manifest.paramsSchema() == null
                || !(manifest.paramsSchema().get("properties") instanceof Map<?, ?> properties)
                || !(properties.get(parameter) instanceof Map<?, ?> property)) {
            return null;
        }
        Object raw = firstNonNull(
                property.get("x-sql-role"), property.get("xSqlRole"), property.get("sqlRole"));
        if (raw == null) {
            return null;
        }
        String normalized = String.valueOf(raw).trim().replace('-', '_').toUpperCase(Locale.ROOT);
        try {
            return SqlRole.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "unsupported SQL role for config." + parameter + ": " + raw, ex);
        }
    }

    private Object firstNonNull(Object... values) {
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private SqlRole defaultRole(String parameter, TemplateKind kind) {
        if ("sql".equals(parameter)) {
            return kind == TemplateKind.RAW_SQL ? SqlRole.QUERY : SqlRole.STATEMENT;
        }
        if (IDENTIFIER_PARAMETERS.contains(parameter)) return SqlRole.IDENTIFIER;
        if (IDENTIFIER_LIST_PARAMETERS.contains(parameter)) return SqlRole.IDENTIFIER_LIST;
        if (LITERAL_PARAMETERS.contains(parameter)) return SqlRole.LITERAL;
        if (NUMBER_PARAMETERS.contains(parameter)) return SqlRole.NUMBER;
        if (TYPE_PARAMETERS.contains(parameter)) return SqlRole.TYPE;
        if (EXPRESSION_PARAMETERS.contains(parameter)) return SqlRole.EXPRESSION;
        if (EXPRESSION_LIST_PARAMETERS.contains(parameter)) return SqlRole.EXPRESSION_LIST;
        if ("mapping".equals(parameter)) return SqlRole.MAPPING;
        if ("mode".equals(parameter)) return SqlRole.FUNCTION;
        return SqlRole.LITERAL;
    }

    private String renderValue(JsonNode value, String parameter, SqlRole role) {
        return switch (role) {
            case IDENTIFIER -> quoteQualifiedIdentifier(requiredText(value, parameter));
            case IDENTIFIER_LIST -> renderIdentifierList(value, parameter);
            case LITERAL -> renderLiteral(value, parameter);
            case LITERAL_LIST -> renderLiteralList(value, parameter);
            case NUMBER -> renderNumber(value, parameter);
            case TYPE -> renderType(value, parameter);
            case FUNCTION -> renderFunction(value, parameter);
            case EXPRESSION -> renderExpression(value, parameter);
            case EXPRESSION_LIST -> renderExpressionList(value, parameter);
            case MAPPING -> renderMapping(value, parameter);
            case QUERY -> renderQuery(value, parameter);
            case STATEMENT -> renderStatement(value, parameter);
        };
    }

    private String renderIdentifierList(JsonNode value, String parameter) {
        List<String> identifiers = new ArrayList<>();
        if (value.isArray()) {
            value.forEach(item -> identifiers.add(quoteQualifiedIdentifier(requiredText(item, parameter))));
        } else if (value.isTextual()) {
            for (String item : value.asText().split(",")) {
                identifiers.add(quoteQualifiedIdentifier(item));
            }
        } else {
            throw invalidType(parameter, "an identifier or identifier array");
        }
        if (identifiers.isEmpty()) {
            throw new IllegalArgumentException("operator config." + parameter + " must not be empty");
        }
        return String.join(", ", identifiers);
    }

    private String renderLiteral(JsonNode value, String parameter) {
        if (value.isNull()) return "NULL";
        if (value.isNumber() || value.isBoolean()) return value.asText().toUpperCase(Locale.ROOT);
        if (!value.isTextual()) throw invalidType(parameter, "a scalar literal");
        return quoteLiteral(value.asText());
    }

    private String renderLiteralList(JsonNode value, String parameter) {
        if (!value.isArray()) return renderLiteral(value, parameter);
        List<String> literals = new ArrayList<>();
        value.forEach(item -> literals.add(renderLiteral(item, parameter)));
        if (literals.isEmpty()) throw invalidType(parameter, "a non-empty literal array");
        return String.join(", ", literals);
    }

    private String renderNumber(JsonNode value, String parameter) {
        String raw = value.isNumber() || value.isTextual() ? value.asText().trim() : "";
        if (!NUMBER.matcher(raw).matches()) {
            throw invalidType(parameter, "a finite decimal number");
        }
        return raw;
    }

    private String renderType(JsonNode value, String parameter) {
        String raw = requiredText(value, parameter).toUpperCase(Locale.ROOT);
        if (!SQL_TYPE.matcher(raw).matches()) {
            throw new IllegalArgumentException("operator config." + parameter
                    + " is not an allowed Spark SQL type: " + raw);
        }
        return raw;
    }

    private String renderFunction(JsonNode value, String parameter) {
        String raw = requiredText(value, parameter).toLowerCase(Locale.ROOT);
        if (!FUNCTION_WHITELIST.contains(raw)) {
            throw new IllegalArgumentException("operator config." + parameter
                    + " is not an allowed SQL function: " + raw);
        }
        return raw;
    }

    private String renderExpression(JsonNode value, String parameter) {
        if (value.isArray()) return renderExpressionList(value, parameter);
        String raw = requiredText(value, parameter);
        validateExpression(raw, "operator config." + parameter);
        return raw;
    }

    private String renderExpressionList(JsonNode value, String parameter) {
        List<String> expressions = new ArrayList<>();
        if (value.isArray()) {
            value.forEach(item -> {
                String raw = requiredText(item, parameter);
                validateExpression(raw, "operator config." + parameter);
                expressions.add(raw);
            });
        } else {
            String raw = requiredText(value, parameter);
            validateExpression(raw, "operator config." + parameter);
            expressions.add(raw);
        }
        if (expressions.isEmpty()) throw invalidType(parameter, "a non-empty expression array");
        return String.join(" ", expressions);
    }

    private String renderMapping(JsonNode value, String parameter) {
        if (!value.isObject() || value.isEmpty()) {
            throw invalidType(parameter, "a non-empty identifier mapping object");
        }
        List<String> projections = new ArrayList<>();
        value.fields().forEachRemaining(entry -> projections.add(
                quoteQualifiedIdentifier(entry.getKey()) + " AS "
                        + quoteIdentifier(requiredText(entry.getValue(), parameter))));
        return String.join(", ", projections);
    }

    private String renderQuery(JsonNode value, String parameter) {
        String raw = requiredText(value, parameter);
        validateSqlBoundaries(raw, "operator config." + parameter);
        validateNoForbiddenFunctions(raw, "operator config." + parameter);
        if (!QUERY_PREFIX.matcher(raw.trim()).matches()) {
            throw new IllegalArgumentException("operator config." + parameter
                    + " must be a single SELECT/WITH query");
        }
        return raw.trim();
    }

    private String renderStatement(JsonNode value, String parameter) {
        String raw = requiredText(value, parameter);
        validateSqlBoundaries(raw, "operator config." + parameter);
        validateNoForbiddenFunctions(raw, "operator config." + parameter);
        if (!STATEMENT_PREFIX.matcher(raw.trim()).matches()) {
            throw new IllegalArgumentException("operator config." + parameter
                    + " must be one allowed Spark SQL statement");
        }
        return raw.trim();
    }

    private void validateTemplateDelimiters(String template) {
        int cursor = 0;
        while (cursor < template.length()) {
            int open = template.indexOf("{{", cursor);
            int close = template.indexOf("}}", cursor);
            if (open < 0) {
                if (close >= 0) {
                    throw new IllegalArgumentException("operator template contains unmatched }}");
                }
                return;
            }
            if (close >= 0 && close < open) {
                throw new IllegalArgumentException("operator template contains unmatched }}");
            }
            close = template.indexOf("}}", open + 2);
            if (close < 0) {
                throw new IllegalArgumentException("operator template contains unmatched {{");
            }
            String expression = template.substring(open + 2, close);
            if (expression.contains("{{") || expression.contains("}}")) {
                throw new IllegalArgumentException("operator template placeholders cannot be nested");
            }
            cursor = close + 2;
        }
    }

    private void validateStaticTemplate(TemplateKind kind, String template) {
        String skeleton = PLACEHOLDER.matcher(template).replaceAll(" ");
        validateSqlBoundaries(skeleton, "operator template");
        validateNoForbiddenFunctions(skeleton, "operator template");
        if (kind == TemplateKind.SELECT_EXPR
                || kind == TemplateKind.COLUMN_EXPR
                || kind == TemplateKind.FILTER) {
            Matcher forbidden = EXPRESSION_FORBIDDEN.matcher(skeleton);
            if (forbidden.find()) {
                throw new IllegalArgumentException("operator template contains forbidden SQL keyword: "
                        + forbidden.group());
            }
            validateWhitelistedFunctions(skeleton, "operator template");
        }
    }

    private void validateRenderedSql(TemplateKind kind, String rendered) {
        validateNoForbiddenFunctions(rendered, "rendered operator SQL");
        if (kind == TemplateKind.RAW_SQL) {
            String query = stripOuterParentheses(rendered);
            validateSqlBoundaries(query, "rendered RAW_SQL");
            if (!QUERY_PREFIX.matcher(query).matches()) {
                throw new IllegalArgumentException("RAW_SQL must render one SELECT/WITH query");
            }
        } else if (kind == TemplateKind.SPARK_SQL) {
            validateSqlBoundaries(rendered, "rendered SPARK_SQL");
            if (!STATEMENT_PREFIX.matcher(rendered).matches()) {
                throw new IllegalArgumentException("SPARK_SQL must render one allowed Spark SQL statement");
            }
        } else if (kind == TemplateKind.SPARK_SINK) {
            String lower = rendered.toLowerCase(Locale.ROOT);
            boolean sinkDsl = (lower.startsWith("write_iceberg(")
                    || lower.startsWith("create_or_replace_view(")
                    || lower.startsWith("merge_into(")) && rendered.endsWith(")");
            if (!sinkDsl) {
                validateSqlBoundaries(rendered, "rendered SPARK_SINK");
                if (!STATEMENT_PREFIX.matcher(rendered).matches()) {
                    throw new IllegalArgumentException("SPARK_SINK must render a supported sink directive or SQL");
                }
            }
        }
    }

    private void validateExpression(String raw, String source) {
        validateSqlBoundaries(raw, source);
        String code = sqlCodeOutsideQuotedText(raw, source);
        int depth = 0;
        for (int index = 0; index < code.length(); index++) {
            char current = code.charAt(index);
            if (current == '(') {
                depth++;
            } else if (current == ')' && --depth < 0) {
                throw new IllegalArgumentException(source + " contains unbalanced parentheses");
            }
        }
        if (depth != 0) {
            throw new IllegalArgumentException(source + " contains unbalanced parentheses");
        }
        Matcher forbidden = EXPRESSION_FORBIDDEN.matcher(code);
        if (forbidden.find()) {
            throw new IllegalArgumentException(source + " contains forbidden SQL keyword: "
                    + forbidden.group());
        }
        validateWhitelistedFunctions(code, source);
    }

    private void validateWhitelistedFunctions(String code, String source) {
        Matcher functions = FUNCTION_CALL.matcher(code);
        while (functions.find()) {
            String function = functions.group(1).toLowerCase(Locale.ROOT);
            if (!EXPRESSION_FUNCTION_WHITELIST.contains(function)) {
                throw new IllegalArgumentException(source
                        + " contains non-whitelisted SQL function: " + function);
            }
        }
    }

    private void validateNoForbiddenFunctions(String raw, String source) {
        String code = sqlCodeOutsideQuotedText(raw, source);
        Matcher functions = FUNCTION_CALL.matcher(code);
        while (functions.find()) {
            String function = functions.group(1).toLowerCase(Locale.ROOT);
            if (FORBIDDEN_SQL_FUNCTIONS.contains(function)) {
                throw new IllegalArgumentException(source
                        + " contains forbidden SQL function: " + function);
            }
        }
    }

    private void validateRequiredConfig(OperatorManifestDTO manifest, JsonNode config) {
        if (manifest.paramsSchema() == null) {
            return;
        }
        Object rawRequired = manifest.paramsSchema().get("required");
        if (!(rawRequired instanceof Iterable<?> required)) {
            return;
        }
        for (Object rawParameter : required) {
            String parameter = String.valueOf(rawParameter);
            JsonNode value = config.get(parameter);
            if (value == null || value.isMissingNode() || value.isNull()) {
                throw new IllegalArgumentException(
                        "operator config missing required parameter: " + parameter);
            }
        }
    }

    private String sqlCodeOutsideQuotedText(String raw, String source) {
        StringBuilder code = new StringBuilder(raw.length());
        char quote = 0;
        for (int index = 0; index < raw.length(); index++) {
            char current = raw.charAt(index);
            if (quote == 0 && (current == '\'' || current == '`')) {
                quote = current;
                code.append(' ');
                continue;
            }
            if (quote != 0) {
                code.append(' ');
                if (quote == '\'' && current == '\\' && index + 1 < raw.length()) {
                    code.append(' ');
                    index++;
                    continue;
                }
                if (current == quote) {
                    if (index + 1 < raw.length() && raw.charAt(index + 1) == quote) {
                        code.append(' ');
                        index++;
                    } else {
                        quote = 0;
                    }
                }
                continue;
            }
            code.append(current);
        }
        if (quote != 0) {
            throw new IllegalArgumentException(source + " contains an unclosed quoted value");
        }
        return code.toString();
    }

    private void validateSqlBoundaries(String raw, String source) {
        if (raw == null || raw.length() > 65_536) {
            throw new IllegalArgumentException(source + " exceeds the SQL size limit");
        }
        if (raw.indexOf(';') >= 0 || raw.contains("--") || raw.contains("/*") || raw.contains("*/")) {
            throw new IllegalArgumentException(source + " must not contain statement delimiters or SQL comments");
        }
        if (raw.chars().anyMatch(ch -> Character.isISOControl(ch) && !Character.isWhitespace(ch))) {
            throw new IllegalArgumentException(source + " must not contain control characters");
        }
    }

    private JsonNode requiredConfig(JsonNode config, String parameter) {
        JsonNode value = config.get(parameter);
        if (value == null || value.isMissingNode() || value.isNull()) {
            throw new IllegalArgumentException("operator config missing required parameter: " + parameter);
        }
        return value;
    }

    private String requiredConfigText(JsonNode config, String parameter) {
        return requiredText(requiredConfig(config, parameter), parameter);
    }

    private String requiredText(JsonNode value, String parameter) {
        if (value == null || !value.isTextual() || !StringUtils.hasText(value.asText())) {
            throw invalidType(parameter, "a non-empty string");
        }
        return value.asText().trim();
    }

    private IllegalArgumentException invalidType(String parameter, String expected) {
        return new IllegalArgumentException("operator config." + parameter + " must be " + expected);
    }

    private enum SqlRole {
        IDENTIFIER,
        IDENTIFIER_LIST,
        LITERAL,
        LITERAL_LIST,
        NUMBER,
        TYPE,
        FUNCTION,
        EXPRESSION,
        EXPRESSION_LIST,
        MAPPING,
        QUERY,
        STATEMENT
    }
}
