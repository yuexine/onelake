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

    static String targetFqn(PipelineTask task, JsonNode config) {
        String configured = firstText(
                text(config.path("targetModelFqn")),
                text(config.path("target_model_fqn")),
                text(config.path("modelFqn"))
        );
        return firstText(configured, task == null ? null : task.getTargetFqn());
    }

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

    private static String pythonString(String value) {
        try {
            return JsonUtil.mapper().writeValueAsString(value == null ? "" : value);
        } catch (Exception e) {
            return "\"\"";
        }
    }
}
