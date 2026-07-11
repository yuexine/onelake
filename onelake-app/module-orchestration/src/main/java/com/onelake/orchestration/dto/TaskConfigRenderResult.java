package com.onelake.orchestration.dto;

import com.fasterxml.jackson.databind.JsonNode;

/** 节点开始执行前完成最终参数替换的配置快照。 */
public record TaskConfigRenderResult(
        JsonNode config
) {
}
