package com.onelake.modeling.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record BusinessTermBindingRequest(
    UUID assetId,
    String assetFqn,
    String columnName,
    String relationType,
    String source,
    BigDecimal confidence
) {}
