package com.onelake.modeling.dto;

import java.util.List;
import java.util.UUID;

public record DwdModelCompileDTO(
    UUID modelId,
    String dbtModelName,
    String materialization,
    String sqlPath,
    String schemaPath,
    String sourcePath,
    UUID orchestrationDagId,
    String dagsterJob,
    String pipelineMode,
    Integer operatorGraphVersion,
    String operatorGraph,
    String resourceGroup,
    String computeProfile,
    String engine,
    String costPolicy,
    String compiledSql,
    List<String> dependencies,
    List<String> outputColumns
) {}
