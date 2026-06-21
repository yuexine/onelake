package com.onelake.dataservice.dto;

import java.util.List;
import java.util.Map;

public record SqlApiDebugResultDTO(
    List<SqlApiColumnDTO> columns,
    List<Map<String, Object>> rows,
    long durationMs,
    long rowCount,
    boolean truncated
) {
    public record SqlApiColumnDTO(
        String name,
        String type
    ) {}
}
