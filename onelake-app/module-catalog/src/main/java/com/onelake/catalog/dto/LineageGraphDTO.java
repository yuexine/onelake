package com.onelake.catalog.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 血缘图查询返回（对应《血缘图模块完善设计方案》§5.2.1）。
 *
 * <p>由 {@code CatalogLineageService.graph()} 返回，前端 X6 直接消费。
 * 节点精简模式（summary=true）省略 {@link Node#columns} / {@link Node#qualityScore} / {@link Node#rowCount}。
 */
public record LineageGraphDTO(
    String rootFqn,
    List<Node> nodes,
    List<Edge> edges
) {

    public record Node(
        String fqn,
        String name,
        String layer,            // SOURCE/ODS/DWD/DWS/ADS/API
        String nodeType,         // TABLE / API / JOB
        String classification,
        BigDecimal qualityScore,
        String ownerName,
        Long rowCount,
        Instant syncedAt,
        List<Column> columns
    ) {}

    public record Column(
        String name,
        String type,
        String classification
    ) {}

    public record Edge(
        String fromFqn,
        String toFqn,
        String jobRef,
        List<ColumnEdge> columnEdges
    ) {}

    public record ColumnEdge(
        String fromColumn,
        String toColumn,
        String transform
    ) {}
}
