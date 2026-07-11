package com.onelake.orchestration.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
}
