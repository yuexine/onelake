package com.onelake.quality.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record QualityRunResultDTO(
    UUID id,
    UUID ruleId,
    UUID jobRunId,
    Boolean passed,
    BigDecimal passRate,
    Long failedRows,
    List<Map<String, Object>> sample,
    Instant checkedAt
) {}
