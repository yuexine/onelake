package com.onelake.orchestration.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.onelake.common.util.JsonUtil;
import com.onelake.orchestration.config.BuiltInOperatorCatalog;
import com.onelake.orchestration.dto.OperatorManifestDTO;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperatorSqlGeneratorTest {

    private final OperatorSqlGenerator generator = new OperatorSqlGenerator();

    @Test
    void rendersSelectExpressionWithEscapedIdentifierList() {
        String sql = generator.generate(
                manifest("SELECT_EXPR", "{{ columns | join(', ') }}"),
                config("{\"columns\":[\"order_id\",\"amount\"]}"));

        assertThat(sql).isEqualTo("`order_id`, `amount`");
    }

    @Test
    void rendersColumnExpressionWithIdentifierAndLiteralEscaping() {
        String sql = generator.generate(
                manifest("COLUMN_EXPR", "coalesce({{ column }}, {{ fillValue }})"),
                config("{\"column\":\"status\",\"fillValue\":\"O'Reilly\"}"));

        assertThat(sql).isEqualTo("coalesce(`status`, 'O\\'Reilly')");
    }

    @Test
    void rendersFilterWithNumericParameters() {
        String sql = generator.generate(
                manifest("FILTER", "{{ column }} BETWEEN {{ min }} AND {{ max }}"),
                config("{\"column\":\"amount\",\"min\":0,\"max\":99.5}"));

        assertThat(sql).isEqualTo("`amount` BETWEEN 0 AND 99.5");
    }

    @Test
    void rendersSingleSparkSqlStatement() {
        String sql = generator.generate(
                manifest("SPARK_SQL", "{{ sql }}"),
                config("{\"sql\":\"SELECT order_id FROM iceberg.ods.orders\"}"));

        assertThat(sql).isEqualTo("SELECT order_id FROM iceberg.ods.orders");
    }

    @Test
    void acceptsTemplateKindAliasUsedByExternalManifests() {
        OperatorManifestDTO manifest = manifest("FILTER", "{{ column }} IS NOT NULL");
        Object kind = manifest.template().get("kind");
        manifest.template().put("kind", " ");
        manifest.template().put("templateKind", kind);

        assertThat(generator.generate(manifest, config("{\"column\":\"order_id\"}")))
                .isEqualTo("`order_id` IS NOT NULL");
    }

    @Test
    void rendersRawSqlQueryInsideManifestWrapper() {
        String sql = generator.generate(
                manifest("RAW_SQL", "({{ sql }})"),
                config("{\"sql\":\"WITH src AS (SELECT 1 AS id) SELECT id FROM src\"}"));

        assertThat(sql).isEqualTo("(WITH src AS (SELECT 1 AS id) SELECT id FROM src)");
    }

    @Test
    void rendersSparkSinkDirectiveWithQualifiedIdentifier() {
        String sql = generator.generate(
                manifest("SPARK_SINK", "write_iceberg({{ targetFqn }})"),
                config("{\"targetFqn\":\"iceberg.dwd.orders\"}"));

        assertThat(sql).isEqualTo("write_iceberg(`iceberg`.`dwd`.`orders`)");
    }

    @Test
    void escapesIdentifierAndLiteralInjectionAsData() {
        String sql = generator.generate(
                manifest("COLUMN_EXPR", "concat({{ column }}, {{ value }})"),
                config("""
                        {"column":"name`; DROP TABLE users;--",
                         "value":"x'); DROP TABLE users; --"}
                        """));

        assertThat(sql)
                .isEqualTo("concat(`name``; DROP TABLE users;--`, 'x\\'); DROP TABLE users; --')");
    }

    @Test
    void escapesBackslashBeforeQuoteSoItCannotCancelSparkLiteralEscaping() {
        String sql = generator.generate(
                manifest("COLUMN_EXPR", "concat({{ column }}, {{ value }})"),
                config("""
                        {"column":"name", "value":"path\\\\'); DROP TABLE users; --"}
                        """));

        assertThat(sql)
                .isEqualTo("concat(`name`, 'path\\\\\\'); DROP TABLE users; --')");
    }

    @Test
    void rejectsStatementInjectionFromRawExpressionAndSqlParameter() {
        assertThatThrownBy(() -> generator.generate(
                manifest("FILTER", "{{ predicate }}"),
                config("{\"predicate\":\"amount > 0; DROP TABLE users\"}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("statement delimiters");

        assertThatThrownBy(() -> generator.generate(
                manifest("FILTER", "{{ predicate }}"),
                config("{\"predicate\":\"amount > 0 UNION SELECT secret FROM credentials\"}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbidden SQL keyword");

        assertThatThrownBy(() -> generator.generate(
                manifest("SPARK_SQL", "{{ sql }}"),
                config("{\"sql\":\"SELECT 1; DROP TABLE users\"}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("statement delimiters");
    }

    @Test
    void rejectsNonWhitelistedExpressionFunctionsAndStaticTemplateFunctions() {
        assertThatThrownBy(() -> generator.generate(
                manifest("FILTER", "{{ predicate }}"),
                config("""
                        {"predicate":"reflect('java.lang.System','setProperty','x','y') IS NOT NULL"}
                        """)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-whitelisted SQL function: reflect");

        assertThatThrownBy(() -> generator.generate(
                manifest("COLUMN_EXPR", "java_method('java.lang.System', 'getProperty', 'user.home')"),
                config("{}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbidden SQL function: java_method");
    }

    @Test
    void rejectsJvmBridgeFunctionsFromSparkSqlAndRawSqlParameters() {
        assertThatThrownBy(() -> generator.generate(
                manifest("SPARK_SQL", "{{ sql }}"),
                config("""
                        {"sql":"SELECT reflect('java.lang.System', 'getProperty', 'user.home')"}
                        """)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbidden SQL function: reflect");

        assertThatThrownBy(() -> generator.generate(
                manifest("RAW_SQL", "({{ sql }})"),
                config("""
                        {"sql":"SELECT java_method('java.lang.System', 'getProperty', 'user.home')"}
                        """)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbidden SQL function: java_method");
    }

    @Test
    void enforcesManifestRequiredParametersEvenWhenTemplateDoesNotReferenceThem() {
        OperatorManifestDTO sink = BuiltInOperatorCatalog.manifests().stream()
                .filter(manifest -> manifest.operatorRef().equals("output.iceberg_table"))
                .findFirst()
                .orElseThrow();

        assertThatThrownBy(() -> generator.generate(
                sink, config("{\"targetFqn\":\"onelake.dwd.orders\"}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing required parameter: partitionBy");
    }

    @Test
    void onlyAllowsWhitelistedTemplateExpressionsAndDoesNotRenderRecursively() {
        assertThatThrownBy(() -> generator.generate(
                manifest("SELECT_EXPR", "{{ arbitrary_code(column) }}"),
                config("{\"column\":\"id\"}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not whitelisted");

        String rendered = generator.generate(
                manifest("SELECT_EXPR", "{{ value }} AS safe_value"),
                config("{\"value\":\"${later}\"}"));
        assertThat(rendered).isEqualTo("'${later}' AS safe_value");
    }

    @Test
    void rendersEveryBuiltInManifestCoveredByG1FromItsExampleConfig() {
        Set<String> supported = Set.of(
                "SELECT_EXPR", "COLUMN_EXPR", "FILTER", "SPARK_SQL", "SPARK_SINK", "RAW_SQL");

        BuiltInOperatorCatalog.manifests().stream()
                .filter(manifest -> supported.contains(String.valueOf(manifest.template().get("kind"))))
                .forEach(manifest -> {
                    Object params = manifest.examples().get(0).get("params");
                    String rendered = generator.generate(manifest, JsonUtil.mapper().valueToTree(params));
                    assertThat(rendered).as(manifest.operatorRef()).isNotBlank();
                });
    }

    private OperatorManifestDTO manifest(String kind, String sql) {
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("kind", kind);
        template.put("sql", sql);
        return new OperatorManifestDTO(
                "custom.generator_test",
                "1.0.0",
                "TRANSFORM",
                "CUSTOM",
                "generator test",
                null,
                null,
                List.of(),
                List.of(Map.of("name", "in", "cardinality", "ONE")),
                Map.of("mode", "DERIVE"),
                Map.of("type", "object", "properties", Map.of()),
                "SPARK",
                template,
                Map.of(),
                Map.of(),
                false,
                Map.of(),
                Map.of("defaultResourceGroup", "spark-default", "engine", "SPARK"),
                List.of());
    }

    private JsonNode config(String json) {
        try {
            return JsonUtil.mapper().readTree(json);
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }
}
