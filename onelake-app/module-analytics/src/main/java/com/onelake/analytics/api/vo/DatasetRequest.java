package com.onelake.analytics.api.vo;

import com.onelake.analytics.domain.enums.SourceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 创建/更新数据集的请求体。
 */
@Data
public class DatasetRequest {

    @NotBlank
    private String name;

    @NotNull
    private SourceType sourceType;

    private String assetFqn;
    private String selectSql;
    private String apiId;
    private List<Map<String, Object>> fieldSchema;
    private String classification;
    private Integer cacheTtlSec;
    private String rowFilter;
}
