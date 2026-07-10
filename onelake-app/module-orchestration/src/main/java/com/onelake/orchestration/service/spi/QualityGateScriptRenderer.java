package com.onelake.orchestration.service.spi;

import com.fasterxml.jackson.databind.JsonNode;
import com.onelake.common.util.JsonUtil;
import com.onelake.orchestration.domain.entity.PipelineTask;
import org.springframework.util.StringUtils;

/**
 * 为 {@code QUALITY_GATE} 流水线节点渲染可执行 PySpark 脚本。
 *
 * <p>生成的脚本会先写入约定命名的 {@code *_quality_check} Iceberg 表，再根据 FAIL
 * 规则抛错，因此用户既能看到运行失败，也能检查质量证据。
 */
final class QualityGateScriptRenderer {

    private QualityGateScriptRenderer() {
    }

    /**
     * 将质量门禁配置渲染为自包含的 PySpark 程序。
     *
     * <p>脚本支持主键、枚举值、数值范围和自定义 SQL 四类规则。它会先把每条规则的
     * PASS/WARN/FAIL 证据写入质量结果表，再对 FAIL 规则抛出异常，使失败本身不会抹掉
     * 可追溯的检查结果。</p>
     *
     * @param task QUALITY_GATE 节点；规则来自其 JSON config
     * @return 可直接放入 Dagster Spark op 配置的 Python 源码
     */
    static String render(PipelineTask task) {
        JsonNode config = parseConfig(task);
        String targetFqn = targetFqn(task, config);
        String qualityTableFqn = qualityTableFqn(targetFqn, config);
        JsonNode gates = config.path("gates");
        String gatesJson = gates.isArray() ? gates.toString() : "[]";

        return """
                import json
                from datetime import datetime, timezone
                from pyspark.sql import SparkSession
                from pyspark.sql.types import StructType, StructField, StringType, LongType

                spark = SparkSession.builder.appName("onelake-quality-gate").getOrCreate()

                MODEL = %s
                QUALITY_TABLE = %s
                GATES = json.loads(%s)

                def sql_ident(name):
                    return "`" + str(name).replace("`", "``") + "`"

                def sql_literal(value):
                    return "'" + str(value).replace("'", "''") + "'"

                def run_count(sql):
                    rows = spark.sql(sql).collect()
                    return int(rows[0][0]) if rows else 0

                def columns_of(gate):
                    raw = gate.get("columns") or []
                    if isinstance(raw, str):
                        return [item.strip() for item in raw.split(",") if item.strip()]
                    return [str(item).strip() for item in raw if str(item).strip()]

                def values_of(gate):
                    raw = gate.get("values") or gate.get("valuesText") or gate.get("acceptedValues") or []
                    if isinstance(raw, str):
                        return [item.strip() for item in raw.split(",") if item.strip()]
                    return [str(item).strip() for item in raw if str(item).strip()]

                def numeric_value(value, label, rule_id):
                    if value is None or str(value).strip() == "":
                        return None
                    try:
                        return float(str(value).strip())
                    except ValueError:
                        add_result(rule_id, "RANGE", label, "FAIL", 1, "CONFIG_ERROR", "range bound is not numeric")
                        return None

                results = []
                failed = []

                def add_result(rule_id, kind, column_name, action, violation_count, status=None, message=""):
                    normalized_action = (action or "FAIL").upper()
                    count = int(violation_count or 0)
                    final_status = status or ("PASS" if count == 0 else ("FAIL" if normalized_action == "FAIL" else "WARN"))
                    results.append((
                        str(rule_id or kind),
                        str(kind or ""),
                        str(column_name or ""),
                        normalized_action,
                        count,
                        final_status,
                        str(message or ""),
                        datetime.now(timezone.utc).isoformat(),
                    ))
                    if final_status == "FAIL":
                        failed.append(f"{rule_id or kind}:{column_name or '*'}={count}")

                for gate in GATES:
                    if not gate.get("enabled", True):
                        continue
                    kind = str(gate.get("kind") or gate.get("type") or "").upper()
                    rule_id = gate.get("id") or gate.get("title") or kind
                    action = str(gate.get("actionOnViolation") or gate.get("action") or "FAIL").upper()
                    columns = columns_of(gate)

                    if kind == "PRIMARY":
                        if not columns:
                            add_result(rule_id, kind, "", "FAIL", 1, "CONFIG_ERROR", "PRIMARY requires columns")
                            continue
                        null_cond = " OR ".join([f"{sql_ident(col)} IS NULL" for col in columns])
                        null_count = run_count(f"SELECT count(*) FROM {MODEL} WHERE {null_cond}")
                        add_result(rule_id, kind, ",".join(columns) + ":not_null", action, null_count)
                        group_cols = ", ".join([sql_ident(col) for col in columns])
                        duplicate_count = run_count(
                            f"SELECT count(*) FROM (SELECT {group_cols}, count(*) c FROM {MODEL} "
                            f"GROUP BY {group_cols} HAVING count(*) > 1) q"
                        )
                        add_result(rule_id, kind, ",".join(columns) + ":unique", action, duplicate_count)
                        continue

                    if kind == "ACCEPTED_VALUES":
                        values = values_of(gate)
                        if not columns or not values:
                            add_result(rule_id, kind, ",".join(columns), "FAIL", 1, "CONFIG_ERROR", "ACCEPTED_VALUES requires columns and values")
                            continue
                        allowed = ", ".join([sql_literal(value) for value in values])
                        for col in columns:
                            expr = f"CAST({sql_ident(col)} AS STRING)"
                            count = run_count(f"SELECT count(*) FROM {MODEL} WHERE {sql_ident(col)} IS NOT NULL AND {expr} NOT IN ({allowed})")
                            add_result(rule_id, kind, col, action, count)
                        continue

                    if kind == "RANGE":
                        if not columns:
                            add_result(rule_id, kind, "", "FAIL", 1, "CONFIG_ERROR", "RANGE requires columns")
                            continue
                        min_value = numeric_value(gate.get("minValue") or gate.get("min"), "min", rule_id)
                        max_value = numeric_value(gate.get("maxValue") or gate.get("max"), "max", rule_id)
                        if min_value is None and max_value is None:
                            add_result(rule_id, kind, ",".join(columns), "FAIL", 1, "CONFIG_ERROR", "RANGE requires minValue or maxValue")
                            continue
                        for col in columns:
                            expr = f"CAST({sql_ident(col)} AS DOUBLE)"
                            checks = []
                            if min_value is not None:
                                checks.append(f"{expr} < {min_value}")
                            if max_value is not None:
                                checks.append(f"{expr} > {max_value}")
                            count = run_count(f"SELECT count(*) FROM {MODEL} WHERE {sql_ident(col)} IS NOT NULL AND ({' OR '.join(checks)})")
                            add_result(rule_id, kind, col, action, count)
                        continue

                    if kind == "CUSTOM_SQL":
                        assertion = str(gate.get("assertionSql") or gate.get("sql") or "").strip().rstrip(";")
                        if not assertion:
                            add_result(rule_id, kind, "", "FAIL", 1, "CONFIG_ERROR", "CUSTOM_SQL requires assertionSql")
                            continue
                        assertion = assertion.replace("{{ model }}", MODEL).replace("${model}", MODEL)
                        count = run_count(f"SELECT count(*) FROM ({assertion}) q")
                        add_result(rule_id, kind, "", action, count)
                        continue

                    add_result(rule_id, kind or "UNKNOWN", ",".join(columns), "FAIL", 1, "CONFIG_ERROR", "unsupported quality gate kind")

                if not results:
                    add_result("__no_enabled_rules__", "NO_RULES", "", "WARN", 0, "SKIPPED", "no enabled quality gates")

                schema = StructType([
                    StructField("rule_id", StringType(), False),
                    StructField("rule_kind", StringType(), False),
                    StructField("column_name", StringType(), True),
                    StructField("action_on_violation", StringType(), False),
                    StructField("violation_count", LongType(), False),
                    StructField("status", StringType(), False),
                    StructField("message", StringType(), True),
                    StructField("checked_at", StringType(), False),
                ])
                spark.createDataFrame(results, schema).writeTo(QUALITY_TABLE).createOrReplace()

                if failed:
                    spark.stop()
                    raise RuntimeError("Quality gate failed: " + "; ".join(failed))

                spark.stop()
                """.formatted(
                pythonString(targetFqn),
                pythonString(qualityTableFqn),
                pythonString(gatesJson)
        ).trim();
    }

    /** 解析被检查模型，配置值优先于任务通用的 targetFqn。 */
    static String targetFqn(PipelineTask task, JsonNode config) {
        String configured = firstText(
                text(config.path("targetModelFqn")),
                text(config.path("target_model_fqn")),
                text(config.path("modelFqn"))
        );
        return firstText(configured, task == null ? null : task.getTargetFqn());
    }

    /**
     * 解析质量结果表；未显式配置时在目标表名后追加 {@code _quality_check}。
     */
    private static String qualityTableFqn(String targetFqn, JsonNode config) {
        String configured = firstText(
                text(config.path("qualityTableFqn")),
                text(config.path("quality_table_fqn"))
        );
        if (StringUtils.hasText(configured)) {
            return configured;
        }
        if (!StringUtils.hasText(targetFqn)) {
            return "onelake.dwd.quality_check";
        }
        String trimmed = targetFqn.trim();
        int dot = trimmed.lastIndexOf('.');
        if (dot < 0 || dot == trimmed.length() - 1) {
            return trimmed + "_quality_check";
        }
        return trimmed.substring(0, dot + 1) + trimmed.substring(dot + 1) + "_quality_check";
    }

    /**
     * 容错解析节点配置。编译阶段负责报告配置错误，渲染器只保证不会因空配置而崩溃。
     */
    private static JsonNode parseConfig(PipelineTask task) {
        if (task == null || !StringUtils.hasText(task.getConfig())) {
            return JsonUtil.mapper().createObjectNode();
        }
        try {
            JsonNode node = JsonUtil.mapper().readTree(task.getConfig());
            return node == null || !node.isObject() ? JsonUtil.mapper().createObjectNode() : node;
        } catch (Exception ignored) {
            return JsonUtil.mapper().createObjectNode();
        }
    }

    private static String text(JsonNode node) {
        return node != null && node.isTextual() ? node.asText("") : "";
    }

    private static String firstText(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    /**
     * 使用 JSON 字符串编码生成合法 Python 字面量，避免表名或规则 JSON 破坏脚本结构。
     */
    private static String pythonString(String value) {
        try {
            return JsonUtil.mapper().writeValueAsString(value == null ? "" : value);
        } catch (Exception e) {
            return "\"\"";
        }
    }
}
