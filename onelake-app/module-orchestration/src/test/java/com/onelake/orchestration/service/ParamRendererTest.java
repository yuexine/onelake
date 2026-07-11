package com.onelake.orchestration.service;

import com.onelake.orchestration.domain.enums.TriggerType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParamRendererTest {

    @Test
    void replacementValuesAreNotRenderedRecursivelyRegardlessOfMapOrder() {
        Map<String, String> firstOrder = new LinkedHashMap<>();
        firstOrder.put("a", "${b}");
        firstOrder.put("b", "resolved");
        Map<String, String> reverseOrder = new LinkedHashMap<>();
        reverseOrder.put("b", "resolved");
        reverseOrder.put("a", "${b}");

        assertThat(ParamRenderer.render("select '${a}'", firstOrder))
                .isEqualTo("select '${b}'");
        assertThat(ParamRenderer.render("select '${a}'", reverseOrder))
                .isEqualTo("select '${b}'");
    }

    @Test
    void treatsReplacementCharactersLiterallyAndKeepsMissingPlaceholders() {
        assertThat(ParamRenderer.render(
                "select '${path}', '${missing}'",
                Map.of("path", "s3://bucket/$daily\\input")))
                .isEqualTo("select 's3://bucket/$daily\\input', '${missing}'");
    }

    @Test
    void rendersDayOffsetsAndCycleTimeInDagTimezone() {
        RunContext context = context(
                "2026-01-31T16:00:00Z",
                "2026-02-01T16:00:00Z");

        assertThat(ParamRenderer.render(
                "${bizdate}|${bizdate-1}|${bizdate+1}|${cyctime}", context, Map.of()))
                .isEqualTo("2026-02-01|2026-01-31|2026-02-02|2026-02-02T00:00:00+08:00");
    }

    @Test
    void offsetsHourlyPartitionsByNaturalHour() {
        RunContext context = context(
                "2026-02-01T16:00:00Z",
                "2026-02-01T17:00:00Z");

        assertThat(ParamRenderer.render(
                "${bizdate-1:yyyy-MM-dd HH}", context, Map.of()))
                .isEqualTo("2026-02-01 23");
    }

    @Test
    void offsetsMonthlyPartitionsAcrossMonthEnd() {
        RunContext context = context(
                "2026-01-30T16:00:00Z",
                "2026-02-27T16:00:00Z");

        assertThat(ParamRenderer.render(
                "${bizdate}|${bizdate+1}|${bizdate-1}", context, Map.of()))
                .isEqualTo("2026-01-31|2026-02-28|2025-12-31");
    }

    @Test
    void appliesCustomBizdateFormat() {
        RunContext context = context(
                "2026-06-30T16:00:00Z",
                "2026-07-01T16:00:00Z");

        assertThat(ParamRenderer.render(
                "dt=${bizdate:yyyyMMdd}", context, Map.of()))
                .isEqualTo("dt=20260701");
    }

    @Test
    void differentLogicalDatesProduceDifferentDeterministicSql() {
        String sql = "select * from orders where dt = '${bizdate}'";
        RunContext first = context("2026-07-01T16:00:00Z", "2026-07-02T16:00:00Z");
        RunContext second = context("2026-07-02T16:00:00Z", "2026-07-03T16:00:00Z");

        assertThat(ParamRenderer.render(sql, first, Map.of()))
                .isEqualTo("select * from orders where dt = '2026-07-02'");
        assertThat(ParamRenderer.render(sql, second, Map.of()))
                .isEqualTo("select * from orders where dt = '2026-07-03'");
    }

    @Test
    void reportsInvalidTimeExpressionsAndMissingTimeContextClearly() {
        assertThatThrownBy(() -> ParamRenderer.validate("select '${bizdate--1}'"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("参数表达式非法 ${bizdate--1}");
        assertThatThrownBy(() -> ParamRenderer.validate("select '${bizdate:yyyy#MM}'"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("时间格式非法");
        assertThatThrownBy(() -> ParamRenderer.render("${bizdate}", RunContext.empty(TriggerType.MANUAL), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RunContext.logicalDate");
    }

    private RunContext context(String logicalDate, String dataIntervalEnd) {
        Instant logical = Instant.parse(logicalDate);
        return new RunContext(
                logical,
                logical,
                Instant.parse(dataIntervalEnd),
                "Asia/Shanghai",
                "NORMAL",
                null,
                TriggerType.BACKFILL);
    }
}
