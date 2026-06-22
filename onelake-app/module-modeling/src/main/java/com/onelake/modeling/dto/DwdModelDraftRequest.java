package com.onelake.modeling.dto;

import java.util.List;

public record DwdModelDraftRequest(
    String name,
    String domain,
    String sourceFqn,
    String targetFqn,
    String materialization,
    String uniqueKey,
    String incrementalColumn,
    String partitionExpr,
    List<ColumnMappingRequest> columnMappings
) {
    public record ColumnMappingRequest(
        String source,
        String target,
        String sourceType,
        String targetType,
        String expression,
        Boolean primaryKey,
        String classification,
        String piiType,
        String suggestLevel
    ) {}
}
