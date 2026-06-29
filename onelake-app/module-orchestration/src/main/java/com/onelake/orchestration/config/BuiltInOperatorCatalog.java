package com.onelake.orchestration.config;

import com.onelake.orchestration.domain.enums.OperatorCategory;
import com.onelake.orchestration.dto.OperatorManifestDTO;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.onelake.orchestration.domain.enums.OperatorCategory.AGG;
import static com.onelake.orchestration.domain.enums.OperatorCategory.ENCRYPT;
import static com.onelake.orchestration.domain.enums.OperatorCategory.GOVERN;
import static com.onelake.orchestration.domain.enums.OperatorCategory.INPUT;
import static com.onelake.orchestration.domain.enums.OperatorCategory.JOIN;
import static com.onelake.orchestration.domain.enums.OperatorCategory.MASK;
import static com.onelake.orchestration.domain.enums.OperatorCategory.OUTPUT;
import static com.onelake.orchestration.domain.enums.OperatorCategory.QUALITY_GATE;
import static com.onelake.orchestration.domain.enums.OperatorCategory.STANDARD;
import static com.onelake.orchestration.domain.enums.OperatorCategory.TRANSFORM;

public final class BuiltInOperatorCatalog {

    private BuiltInOperatorCatalog() {
    }

    private record BuiltinSpec(
        String ref,
        OperatorCategory category,
        String displayName,
        String description,
        String outputMode,
        String templateKind,
        String sql,
        List<String> params
    ) {
    }

    private static final List<BuiltinSpec> SPECS = List.of(
        spec("input.ods_table", INPUT, "ODS 表输入", "读取 ODS 分层表作为算子图起点", "DERIVE", "SELECT_EXPR", "{{ source('ods', sourceFqn) }}", "sourceFqn"),
        spec("input.dwd_table", INPUT, "DWD 表输入", "读取已有 DWD 表作为输入", "DERIVE", "SELECT_EXPR", "{{ dwd(modelRef) }}", "modelRef"),
        spec("input.sql_query", INPUT, "SQL 查询输入", "读取经只读校验的 SQL 子查询", "DERIVE", "RAW_SQL", "({{ sql }})", "sql"),
        spec("output.iceberg_table", OUTPUT, "Iceberg 表输出", "将结果物化为 Iceberg 表", "PASSTHROUGH", "SPARK_SINK", "write_iceberg({{ targetFqn }})", "targetFqn", "partitionBy"),
        spec("output.view", OUTPUT, "视图输出", "将结果物化为视图", "PASSTHROUGH", "SPARK_SINK", "create_or_replace_view({{ targetFqn }})", "targetFqn"),
        spec("output.incremental_merge", OUTPUT, "增量合并输出", "按唯一键和增量字段合并写入", "PASSTHROUGH", "SPARK_SINK", "merge_into({{ targetFqn }}, {{ uniqueKey }})", "uniqueKey", "incrementalColumn", "strategy"),

        spec("transform.select_columns", TRANSFORM, "选择字段", "保留指定字段集合", "DERIVE", "SELECT_EXPR", "{{ columns | join(', ') }}", "columns"),
        spec("transform.rename_columns", TRANSFORM, "重命名字段", "按映射关系重命名字段", "DERIVE", "SELECT_EXPR", "{{ mapping }}", "mapping"),
        spec("transform.cast_type", TRANSFORM, "类型转换", "将字段转换为目标类型", "PASSTHROUGH_MODIFY", "COLUMN_EXPR", "CAST({{ column }} AS {{ targetType }})", "column", "targetType"),
        spec("transform.derive_column", TRANSFORM, "派生字段", "用受控表达式生成新字段", "DERIVE", "SELECT_EXPR", "{{ expr }} AS {{ name }}", "name", "expr"),
        spec("transform.constant_column", TRANSFORM, "常量字段", "追加固定值字段", "DERIVE", "SELECT_EXPR", "CAST({{ value }} AS {{ type }}) AS {{ name }}", "name", "value", "type"),
        spec("transform.concat_columns", TRANSFORM, "字段拼接", "按分隔符拼接多个字段", "DERIVE", "SELECT_EXPR", "concat_ws({{ sep }}, {{ columns | join(', ') }}) AS {{ as }}", "columns", "sep", "as"),
        spec("transform.split_column", TRANSFORM, "字段拆分", "按分隔符拆分字段", "DERIVE", "SELECT_EXPR", "split_part({{ column }}, {{ delimiter }}, n)", "column", "delimiter", "outputs"),
        spec("transform.case_when", TRANSFORM, "条件分支", "按 CASE WHEN 规则派生字段", "DERIVE", "SELECT_EXPR", "CASE {{ cases }} ELSE {{ else }} END AS {{ as }}", "cases", "else", "as"),
        spec("transform.rename_by_standard", TRANSFORM, "按标准命名", "按数据标准映射字段名", "DERIVE", "SELECT_EXPR", "{{ standardId }}", "standardId"),
        spec("transform.reorder_columns", TRANSFORM, "字段排序", "调整字段输出顺序", "DERIVE", "SELECT_EXPR", "{{ order | join(', ') }}", "order"),
        spec("transform.spark_sql", TRANSFORM, "Spark SQL 执行", "执行编译后的 Spark SQL 并产出中间表", "DERIVE", "SPARK_SQL", "{{ sql }}", "sql"),

        spec("govern.trim_whitespace", GOVERN, "去除空白", "去除字段首尾空白字符", "PASSTHROUGH_MODIFY", "COLUMN_EXPR", "trim({{ column }})", "columns"),
        spec("govern.fillna", GOVERN, "空值填充", "用默认值填充空值", "PASSTHROUGH_MODIFY", "COLUMN_EXPR", "coalesce({{ column }}, {{ fillValue }})", "column", "fillValue"),
        spec("govern.drop_null", GOVERN, "过滤空值", "过滤指定字段为空的行", "PASSTHROUGH", "FILTER", "{{ column }} IS NOT NULL", "columns"),
        spec("govern.dedup", GOVERN, "去重", "按主键和排序字段保留一条记录", "PASSTHROUGH", "FILTER", "row_number() over(partition by {{ keys }} order by {{ orderBy }}) = 1", "keys", "orderBy"),
        spec("govern.filter_rows", GOVERN, "行过滤", "按受控谓词过滤行", "PASSTHROUGH", "FILTER", "{{ predicate }}", "predicate"),
        spec("govern.drop_required_missing", GOVERN, "必填缺失过滤", "过滤必填字段缺失记录", "PASSTHROUGH", "FILTER", "{{ requiredColumns }} IS NOT NULL", "requiredColumns"),
        spec("govern.normalize_case", GOVERN, "大小写规整", "统一字段大小写", "PASSTHROUGH_MODIFY", "COLUMN_EXPR", "{{ mode }}({{ column }})", "column", "mode"),
        spec("govern.standardize_enum", GOVERN, "枚举标准化", "按码表标准化枚举值", "PASSTHROUGH_MODIFY", "COLUMN_EXPR", "CASE {{ dictType }} END", "column", "dictType"),
        spec("govern.outlier_filter", GOVERN, "异常值过滤", "按范围或统计方法过滤异常值", "PASSTHROUGH", "FILTER", "{{ column }} BETWEEN {{ min }} AND {{ max }}", "column", "min", "max", "method"),
        spec("govern.regex_replace", GOVERN, "正则替换", "用正则表达式清洗字段", "PASSTHROUGH_MODIFY", "COLUMN_EXPR", "regexp_replace({{ column }}, {{ pattern }}, {{ replacement }})", "column", "pattern", "replacement"),

        spec("standard.codebook_mapping", STANDARD, "码表映射", "按字典类型映射标准值", "PASSTHROUGH_MODIFY", "COLUMN_EXPR", "CASE {{ dictType }} END", "column", "dictType"),
        spec("standard.unit_normalize", STANDARD, "单位换算", "按换算系数标准化单位", "PASSTHROUGH_MODIFY", "COLUMN_EXPR", "{{ column }} * {{ factor }}", "column", "fromUnit", "toUnit", "factor"),
        spec("standard.date_format", STANDARD, "日期格式标准化", "解析并输出标准日期类型", "PASSTHROUGH_MODIFY", "COLUMN_EXPR", "date_parse({{ column }}, {{ inputFmt }})", "column", "inputFmt", "outputType"),
        spec("standard.phone_normalize", STANDARD, "手机号规整", "去除分隔符并规整手机号格式", "PASSTHROUGH_MODIFY", "COLUMN_EXPR", "regexp_replace({{ column }}, '[^0-9]', '')", "column"),
        spec("standard.id_card_normalize", STANDARD, "证件号规整", "规整证件号大小写和空白", "PASSTHROUGH_MODIFY", "COLUMN_EXPR", "upper(trim({{ column }}))", "column"),
        spec("standard.address_normalize", STANDARD, "地址标准化", "按行政区划码表规整地址", "PASSTHROUGH_MODIFY", "COLUMN_EXPR", "CASE {{ dictType }} END", "column", "dictType"),

        spec("mask.partial", MASK, "部分掩码", "保留首尾并掩码中间字符", "PASSTHROUGH_MODIFY", "COLUMN_EXPR", "regexp_replace({{ column }}, '^(.{3}).*(.{4})$', '$1****$2')", "column", "keepHead", "keepTail"),
        spec("mask.full", MASK, "全量掩码", "将字段完全替换为掩码字符", "PASSTHROUGH_MODIFY", "COLUMN_EXPR", "repeat('*', length({{ column }}))", "column"),
        spec("mask.name", MASK, "姓名脱敏", "保留姓氏并隐藏名", "PASSTHROUGH_MODIFY", "COLUMN_EXPR", "concat(substr({{ column }}, 1, 1), '**')", "column"),
        spec("mask.id_card", MASK, "身份证脱敏", "按内置 Spark 表达式掩码身份证号", "PASSTHROUGH_MODIFY", "COLUMN_EXPR", "{{ mask_id_card(column) }}", "column"),
        spec("mask.email", MASK, "邮箱脱敏", "隐藏邮箱用户名中间部分", "PASSTHROUGH_MODIFY", "COLUMN_EXPR", "regexp_replace({{ column }}, '^(.).+(@.+)$', '$1***$2')", "column"),
        spec("mask.bankcard", MASK, "银行卡脱敏", "仅保留银行卡后四位", "PASSTHROUGH_MODIFY", "COLUMN_EXPR", "concat('****', substr({{ column }}, -4))", "column"),
        spec("mask.nullify", MASK, "置空脱敏", "将敏感字段置空", "PASSTHROUGH_MODIFY", "COLUMN_EXPR", "NULL", "column"),
        spec("mask.generalize", MASK, "泛化脱敏", "将连续值泛化到区间或桶", "PASSTHROUGH_MODIFY", "COLUMN_EXPR", "CASE {{ buckets }} END", "column", "buckets"),

        spec("encrypt.sha256", ENCRYPT, "SHA256 哈希", "对字段进行不可逆 SHA256 哈希", "PASSTHROUGH_MODIFY", "COLUMN_EXPR", "to_hex(sha256(cast({{ column }} || {{ salt }} as varbinary)))", "column", "salt"),
        spec("encrypt.md5", ENCRYPT, "MD5 哈希", "对字段进行不可逆 MD5 哈希", "PASSTHROUGH_MODIFY", "COLUMN_EXPR", "md5({{ column }})", "column"),
        spec("encrypt.aes", ENCRYPT, "AES 加密", "通过密钥引用执行 AES 加密", "PASSTHROUGH_MODIFY", "COLUMN_EXPR", "aes_encrypt({{ column }}, {{ keyRef }})", "column", "keyRef"),
        spec("encrypt.fpe", ENCRYPT, "保格加密", "通过密钥引用执行保格式加密", "PASSTHROUGH_MODIFY", "COLUMN_EXPR", "fpe_encrypt({{ column }}, {{ keyRef }})", "column", "keyRef"),
        spec("encrypt.tokenize", ENCRYPT, "令牌化", "通过映射表生成可查令牌", "PASSTHROUGH_MODIFY", "COLUMN_EXPR", "tokenize({{ column }}, {{ tokenTable }})", "column", "tokenTable"),

        spec("agg.group_aggregate", AGG, "分组聚合", "按维度和指标聚合", "AGGREGATE", "AGG", "GROUP BY {{ groupBy }}", "groupBy", "aggregations"),
        spec("agg.window_function", AGG, "窗口函数", "按分区和排序派生窗口指标", "DERIVE", "SELECT_EXPR", "{{ fn }} over(partition by {{ partitionBy }} order by {{ orderBy }})", "partitionBy", "orderBy", "fn"),
        spec("agg.pivot", AGG, "行转列", "按 key/value 将行转为列", "AGGREGATE", "AGG", "sum(case when {{ key }} then {{ value }} end)", "key", "value", "columns"),
        spec("agg.unpivot", AGG, "列转行", "将多列展开为 key/value 行", "DERIVE", "SELECT_EXPR", "UNNEST({{ columns }})", "columns", "keyName", "valueName"),
        spec("agg.distinct_count", AGG, "去重计数", "计算指定字段去重数", "AGGREGATE", "AGG", "count(distinct {{ column }})", "groupBy", "column"),
        spec("agg.running_total", AGG, "累计求和", "按窗口计算累计值", "DERIVE", "SELECT_EXPR", "sum({{ column }}) over(partition by {{ partitionBy }} order by {{ orderBy }})", "partitionBy", "orderBy", "column"),

        spec("join.inner", JOIN, "内连接", "按条件执行 INNER JOIN", "DERIVE", "JOIN", "INNER JOIN {{ on }}", "on", "select"),
        spec("join.left", JOIN, "左连接", "按条件执行 LEFT JOIN", "DERIVE", "JOIN", "LEFT JOIN {{ on }}", "on", "select"),
        spec("join.union_all", JOIN, "合并追加", "按字段对齐执行 UNION ALL", "DERIVE", "JOIN", "UNION ALL", "mode"),
        spec("join.lookup_enrich", JOIN, "维表查找补全", "关联维表补充属性字段", "DERIVE", "JOIN", "LEFT JOIN {{ dimRef }} ON {{ on }}", "dimRef", "on", "enrichColumns"),
        spec("join.dedup_merge", JOIN, "去重合并", "多输入按策略合并金标记录", "DERIVE", "JOIN", "MERGE BY {{ keys }}", "keys", "orderBy", "strategy"),

        spec("gate.not_null", QUALITY_GATE, "非空门禁", "校验字段非空", "ASSERT", "QUALITY_ASSERT", "not_null", "columns"),
        spec("gate.unique", QUALITY_GATE, "唯一门禁", "校验字段唯一", "ASSERT", "QUALITY_ASSERT", "unique", "columns"),
        spec("gate.range", QUALITY_GATE, "范围门禁", "校验数值在范围内", "ASSERT", "QUALITY_ASSERT", "{{ column }} BETWEEN {{ min }} AND {{ max }}", "column", "min", "max"),
        spec("gate.regex", QUALITY_GATE, "正则门禁", "校验字段匹配正则", "ASSERT", "QUALITY_ASSERT", "regexp_like({{ column }}, {{ pattern }})", "column", "pattern"),
        spec("gate.enum", QUALITY_GATE, "枚举门禁", "校验字段值在允许集合内", "ASSERT", "QUALITY_ASSERT", "accepted_values", "column", "values", "dictType"),
        spec("gate.freshness", QUALITY_GATE, "新鲜度门禁", "校验数据更新时间延迟", "ASSERT", "QUALITY_ASSERT", "freshness {{ maxDelay }}", "column", "maxDelay"),
        spec("gate.row_count", QUALITY_GATE, "行数门禁", "校验输出行数范围", "ASSERT", "QUALITY_ASSERT", "row_count between {{ min }} and {{ max }}", "min", "max"),
        spec("gate.referential", QUALITY_GATE, "参照完整性门禁", "校验字段引用存在", "ASSERT", "QUALITY_ASSERT", "relationships to {{ refModel }}.{{ refColumn }}", "column", "refModel", "refColumn"),
        spec("gate.custom_sql", QUALITY_GATE, "自定义 SQL 门禁", "执行只读断言 SQL", "ASSERT", "QUALITY_ASSERT", "{{ assertionSql }}", "assertionSql")
    );

    public static List<OperatorManifestDTO> manifests() {
        return SPECS.stream().map(BuiltInOperatorCatalog::manifest).toList();
    }

    public static int size() {
        return SPECS.size();
    }

    private static BuiltinSpec spec(String ref, OperatorCategory category, String displayName,
                                    String description, String outputMode, String templateKind,
                                    String sql, String... params) {
        return new BuiltinSpec(ref, category, displayName, description, outputMode,
            templateKind, sql, List.of(params));
    }

    private static OperatorManifestDTO manifest(BuiltinSpec spec) {
        Map<String, Object> outputSchema = new LinkedHashMap<>();
        outputSchema.put("mode", spec.outputMode());
        if (spec.outputMode().equals("PASSTHROUGH_MODIFY")) {
            outputSchema.put("modifies", List.of("{{params.column}}"));
        }

        Map<String, Object> template = new LinkedHashMap<>();
        template.put("kind", spec.templateKind());
        template.put("sql", spec.sql());

        Map<String, Object> lineageRule = new LinkedHashMap<>();
        lineageRule.put("type", spec.category() == OperatorCategory.AGG ? "AGGREGATE" : "ONE_TO_ONE");
        lineageRule.put("from", "{{input}}");
        lineageRule.put("to", "{{output}}");

        Map<String, Object> securityRule = new LinkedHashMap<>();
        securityRule.put("effect", securityEffect(spec.category()));

        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("actionOnViolation", spec.category() == QUALITY_GATE ? "FAIL" : null);

        Map<String, Object> resourceHint = new LinkedHashMap<>();
        resourceHint.put("defaultResourceGroup", "spark-default");
        resourceHint.put("engine", "SPARK");

        Map<String, Object> example = new LinkedHashMap<>();
        example.put("title", spec.displayName() + "示例");
        example.put("params", exampleParams(spec.params()));

        return new OperatorManifestDTO(
            spec.ref(),
            "1.0.0",
            spec.category().name(),
            "BUILTIN",
            spec.displayName(),
            spec.description(),
            defaultIcon(spec.category()),
            tags(spec.category()),
            inputPorts(spec),
            outputSchema,
            paramsSchema(spec.params()),
            "SPARK",
            template,
            lineageRule,
            securityRule,
            spec.category() == QUALITY_GATE,
            policy,
            resourceHint,
            List.of(example)
        );
    }

    private static List<Map<String, Object>> inputPorts(BuiltinSpec spec) {
        if (spec.category() == INPUT) {
            return List.of();
        }
        if (spec.category() == JOIN) {
            Map<String, Object> left = port("left", "ONE");
            Map<String, Object> right = port(spec.ref().equals("join.union_all") ? "inputs" : "right",
                spec.ref().equals("join.union_all") ? "MANY" : "ONE");
            return List.of(left, right);
        }
        return List.of(port("in", "ONE"));
    }

    private static Map<String, Object> port(String name, String cardinality) {
        Map<String, Object> port = new LinkedHashMap<>();
        port.put("name", name);
        port.put("cardinality", cardinality);
        port.put("accept", "TABLE");
        return port;
    }

    private static Map<String, Object> paramsSchema(List<String> params) {
        Map<String, Object> schema = new LinkedHashMap<>();
        Map<String, Object> properties = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("required", params);
        for (String param : params) {
            properties.put(param, paramSchema(param));
        }
        schema.put("properties", properties);
        return schema;
    }

    private static Map<String, Object> paramSchema(String param) {
        Map<String, Object> schema = new LinkedHashMap<>();
        if (List.of("columns", "keys", "groupBy", "aggregations", "cases", "buckets",
            "select", "values", "outputs", "partitionBy", "enrichColumns", "requiredColumns",
            "order", "partitionBy").contains(param)) {
            schema.put("type", "array");
            schema.put("items", Map.of("type", "string"));
        } else if (List.of("min", "max", "keepHead", "keepTail", "factor").contains(param)) {
            schema.put("type", "number");
        } else if (param.equals("mapping")) {
            schema.put("type", "object");
        } else {
            schema.put("type", "string");
        }
        schema.put("title", param);
        return schema;
    }

    private static Map<String, Object> exampleParams(List<String> params) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String param : params) {
            Object value = switch (param) {
                case "columns", "keys", "groupBy", "partitionBy", "order", "requiredColumns" -> List.of("order_id");
                case "aggregations" -> List.of("sum(amount) as amount_sum");
                case "cases" -> List.of("when status = 'paid' then 'PAID'");
                case "values" -> List.of("PAID", "CANCELLED");
                case "outputs" -> List.of("part_1", "part_2");
                case "select", "enrichColumns" -> List.of("order_id", "amount");
                case "mapping" -> Map.of("old_name", "new_name");
                case "min" -> 0;
                case "max" -> 100;
                case "keepHead" -> 3;
                case "keepTail" -> 4;
                case "factor" -> 1;
                default -> sampleValue(param);
            };
            values.put(param, value);
        }
        return values;
    }

    private static String sampleValue(String param) {
        return switch (param) {
            case "sourceFqn" -> "ods.ods_codex_orders";
            case "modelRef" -> "dwd_trade_codex_orders_df";
            case "targetFqn" -> "dwd.dwd_trade_codex_orders_df";
            case "column" -> "phone";
            case "targetType", "type", "outputType" -> "VARCHAR";
            case "fillValue" -> "UNKNOWN";
            case "predicate" -> "amount >= 0";
            case "pattern" -> "\\s+";
            case "replacement" -> "";
            case "mode" -> "lower";
            case "dictType" -> "order_status";
            case "salt" -> "tenant-salt";
            case "keyRef" -> "kms://default/customer-key";
            case "tokenTable" -> "security.token_map";
            case "on" -> "left.id = right.id";
            case "fn" -> "row_number()";
            case "sql", "assertionSql" -> "select * from input where amount >= 0";
            case "expr" -> "amount * 100";
            case "name", "as" -> "derived_col";
            case "value" -> "N/A";
            case "sep", "delimiter" -> "-";
            case "else" -> "UNKNOWN";
            case "standardId" -> "std_order";
            case "fromUnit" -> "yuan";
            case "toUnit" -> "cent";
            case "inputFmt" -> "%Y-%m-%d";
            case "keyName" -> "metric";
            case "valueName" -> "value";
            case "strategy" -> "latest";
            case "refModel" -> "dim_customer";
            case "refColumn" -> "customer_id";
            case "maxDelay" -> "PT24H";
            default -> param;
        };
    }

    private static String securityEffect(OperatorCategory category) {
        if (category == MASK) {
            return "DOWNGRADE_ALLOWED";
        }
        if (category == ENCRYPT) {
            return "KEEP";
        }
        return "INHERIT";
    }

    private static String defaultIcon(OperatorCategory category) {
        return switch (category) {
            case INPUT -> "DatabaseOutlined";
            case OUTPUT -> "ExportOutlined";
            case MASK -> "EyeInvisibleOutlined";
            case ENCRYPT -> "SafetyCertificateOutlined";
            case QUALITY_GATE -> "CheckCircleOutlined";
            case JOIN -> "BranchesOutlined";
            case AGG -> "FunctionOutlined";
            default -> "AppstoreOutlined";
        };
    }

    private static List<String> tags(OperatorCategory category) {
        List<String> tags = new ArrayList<>();
        tags.add(category.name());
        tags.add("内置");
        tags.add("SPARK");
        if (category == MASK || category == ENCRYPT) {
            tags.add("安全");
        }
        if (category == QUALITY_GATE) {
            tags.add("质量门禁");
        }
        return tags;
    }
}
