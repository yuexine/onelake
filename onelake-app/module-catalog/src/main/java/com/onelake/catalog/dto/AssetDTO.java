package com.onelake.catalog.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AssetDTO(
    UUID id,
    String fqn,
    String name,
    String type,
    String layer,
    String domain,
    UUID ownerId,
    String ownerName,
    String description,
    List<String> tags,
    String classification,
    BigDecimal qualityScore,
    Integer popularity,
    Integer accessCount,
    Long rows,
    Long sizeBytes,
    List<AssetColumnDTO> columns,
    List<String> partitions,
    String format,
    Instant lastSyncAt,
    Instant syncedAt
) {
    public record AssetColumnDTO(
        String name,
        String type,
        String description,
        String classification,
        String piiType,
        String suggestLevel
    ) {}
}
