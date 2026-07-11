package com.onelake.orchestration.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** Dagster 节点开始执行前请求 Java 侧完成上游输出参数渲染。 */
public record TaskConfigRenderRequest(
        @NotNull JsonNode config,
        @NotNull List<String> upstreamTaskKeys
) {
}
