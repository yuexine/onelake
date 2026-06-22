package com.onelake.catalog.dto.sql;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record SqlExecuteResultDTO(
    UUID historyId,
    String status,
    String trinoQueryId,
    List<SqlColumnDTO> columns,
    List<Map<String, Object>> rows,
    Long durationMs,
    Long scanBytes,
    Long rowCount,
    boolean truncated,
    String error,
    List<String> maskedColumns,
    List<String> securityNotices,
    String errorCode
) {
    public record SqlColumnDTO(
        String name,
        String type
    ) {}
}
