package com.onelake.orchestration.dto;

import com.onelake.orchestration.domain.entity.PipelineParam;

import java.time.Instant;
import java.util.UUID;

/** 三级参数的稳定 API 投影，兼作整组替换保存时的请求项。 */
public record ParamDTO(
        UUID id,
        String scope,
        UUID dagId,
        String taskKey,
        String paramKey,
        String paramValue,
        String valueType,
        String description,
        Instant updatedAt
) {
    public static ParamDTO of(PipelineParam param) {
        return new ParamDTO(
                param.getId(),
                param.getScope(),
                param.getDagId(),
                param.getTaskKey(),
                param.getParamKey(),
                param.getParamValue(),
                param.getValueType(),
                param.getDescription(),
                param.getUpdatedAt());
    }
}
