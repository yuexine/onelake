package com.onelake.catalog.dto;

import java.util.List;

public record AssetMetadataUpdateRequest(
    String description,
    String domain,
    String ownerName,
    List<String> tags,
    List<ColumnMetadataUpdateRequest> columns
) {
    public record ColumnMetadataUpdateRequest(
        String name,
        String description,
        String classification,
        String piiType,
        String suggestLevel,
        Boolean primaryKey
    ) {}
}
