package com.onelake.modeling.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DataModelDTO(
    UUID id,
    String name,
    String layer,
    String domain,
    String sourceFqn,
    String targetFqn,
    String status,
    String materialization,
    String uniqueKey,
    String incrementalColumn,
    String partitionExpr,
    String sqlText,
    String compiledSql,
    String dbtModelName,
    UUID orchestrationDagId,
    String dagsterJob,
    String artifactPath,
    UUID lastRunId,
    String pipelineMode,
    Integer operatorGraphVersion,
    String operatorGraph,
    String resourceGroup,
    String computeProfile,
    String engine,
    String costPolicy,
    UUID ownerId,
    String ownerName,
    Instant createdAt,
    Instant updatedAt,
    List<SourceDTO> sources,
    List<ColumnMappingDTO> columnMappings
) {
    public record SourceDTO(
        UUID id,
        String sourceFqn,
        String sourceType,
        Integer sortNo
    ) {}

    public record ColumnMappingDTO(
        UUID id,
        String source,
        String target,
        String sourceType,
        String targetType,
        String expression,
        Boolean primaryKey,
        String classification,
        String piiType,
        String suggestLevel,
        Integer sortNo
    ) {}
}
