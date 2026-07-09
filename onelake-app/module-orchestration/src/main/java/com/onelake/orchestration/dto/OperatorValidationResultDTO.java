package com.onelake.orchestration.dto;

import java.util.List;

/**
 * 算子 Manifest 或算子图校验结果。
 */
public record OperatorValidationResultDTO(
    boolean ok,
    List<String> errors,
    List<String> warnings
) {
}
