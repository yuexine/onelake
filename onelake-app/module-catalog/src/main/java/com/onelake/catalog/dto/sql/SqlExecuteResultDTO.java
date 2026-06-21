package com.onelake.catalog.dto.sql;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record SqlExecuteResultDTO(
    UUID historyId,
    String status,
    List<SqlColumnDTO> columns,
    List<Map<String, Object>> rows,
    Long durationMs,
    Long scanBytes,
    Long rowCount,
    boolean truncated,
    String error
) {
    public record SqlColumnDTO(
        String name,
        String type
    ) {}
}
