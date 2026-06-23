package com.onelake.orchestration.dto;

import java.util.List;

public record OperatorValidationResultDTO(
    boolean ok,
    List<String> errors,
    List<String> warnings
) {
}
