package com.onelake.analytics.dto;

import com.onelake.analytics.domain.entity.Dataset;
import com.onelake.analytics.domain.enums.SourceType;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 数据集传输对象（不直接暴露 entity）。
 */
@Data
@Builder
public class DatasetDTO {

    private UUID id;
    private UUID tenantId;
    private String name;
    private SourceType sourceType;
    private String assetFqn;
    private String selectSql;
    private UUID apiId;
    private List<FieldSchema> fieldSchema;
    private String classification;
    private Integer cacheTtlSec;
    private String rowFilter;
    private UUID createdBy;
    private Instant createdAt;
    private Instant updatedAt;

    @Data
    @Builder
    public static class FieldSchema {
        private String name;
        private String type;
        private String classification;  // L1..L4
    }

    public static DatasetDTO from(Dataset d) {
        return DatasetDTO.builder()
            .id(d.getId())
            .tenantId(d.getTenantId())
            .name(d.getName())
            .sourceType(d.getSourceType())
            .assetFqn(d.getAssetFqn())
            .selectSql(d.getSelectSql())
            .apiId(d.getApiId())
            .classification(d.getClassification())
            .cacheTtlSec(d.getCacheTtlSec())
            .rowFilter(d.getRowFilter())
            .createdBy(d.getCreatedBy())
            .createdAt(d.getCreatedAt())
            .updatedAt(d.getUpdatedAt())
            .build();
    }
}
