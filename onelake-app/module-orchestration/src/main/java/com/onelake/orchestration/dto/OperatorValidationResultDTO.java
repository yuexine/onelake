package com.onelake.orchestration.dto;

import java.util.List;

/**
 * 算子 Manifest 或算子图校验结果。
 *
 * @param ok 是否通过全部阻断校验
 * @param errors 阻断错误列表
 * @param warnings 非阻断告警列表
 */
public record OperatorValidationResultDTO(
    boolean ok,
    List<String> errors,
    List<String> warnings
) {
}
