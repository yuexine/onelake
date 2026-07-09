package com.onelake.orchestration.dto;

import java.util.List;
import java.util.Map;

/**
 * 算子 Manifest 数据传输对象。
 *
 * <p>该对象是算子注册、版本发布、图校验和前端属性面板共享的契约载体。
 */
public record OperatorManifestDTO(
    String operatorRef,
    String version,
    String category,
    String scope,
    String displayName,
    String description,
    String icon,
    List<String> tags,
    List<Map<String, Object>> inputPorts,
    Map<String, Object> outputSchema,
    Map<String, Object> paramsSchema,
    String compileTarget,
    Map<String, Object> template,
    Map<String, Object> lineageRule,
    Map<String, Object> securityRule,
    Boolean qualityEmit,
    Map<String, Object> policy,
    Map<String, Object> resourceHint,
    List<Map<String, Object>> examples
) {
}
