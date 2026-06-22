package com.onelake.catalog.dto;

import java.util.List;

public record TableCreateRequest(
    String layer,
    String domain,
    String name,
    String description,
    List<ColumnCreateRequest> columns,
    String partitionStrategy,
    String format,
    String compression,
    Integer ttlDays,
    Integer coldStorageAfterDays
) {
    public record ColumnCreateRequest(
        String name,
        String type,
        Boolean primaryKey,
        String classification,
        String comment
    ) {}
}
