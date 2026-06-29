package com.onelake.analytics.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 数据集查询结果（前端渲染使用）。
 * 不缓存整个 QueryResult 对象，只缓存其 JSON 序列化字符串。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryResult {

    private List<Map<String, Object>> rows;
    private List<DatasetDTO.FieldSchema> fields;
    private boolean cacheHit;
    private long durationMs;

    public static QueryResult of(List<Map<String, Object>> rows, List<DatasetDTO.FieldSchema> fields) {
        return QueryResult.builder()
            .rows(rows)
            .fields(fields)
            .cacheHit(false)
            .build();
    }
}
