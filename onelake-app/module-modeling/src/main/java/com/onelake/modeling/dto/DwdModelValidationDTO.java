package com.onelake.modeling.dto;

import java.util.List;

public record DwdModelValidationDTO(
    boolean ok,
    List<String> errors,
    List<String> warnings,
    String compiledSql,
    List<String> dependencies,
    List<String> outputColumns
) {}
