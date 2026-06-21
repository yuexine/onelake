package com.onelake.quality.api.vo;

import java.util.UUID;

public record CreateQualityRuleVO(
    String targetFqn,
    String targetColumn,
    String ruleType,
    String expression,
    String severity,
    String schedule,
    UUID ownerId
) {}
