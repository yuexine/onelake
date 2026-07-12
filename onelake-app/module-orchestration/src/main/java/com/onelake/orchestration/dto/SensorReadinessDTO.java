package com.onelake.orchestration.dto;

import java.time.Instant;

/** Dagster SENSOR 轮询使用的内部资产就绪快照。 */
public record SensorReadinessDTO(
        boolean ready,
        String source,
        String assetFqn,
        String partition,
        Instant readyAt
) {
}
