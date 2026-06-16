package com.onelake.integration.dto;

public record DiscoveredColumnDTO(
    String name,
    String type,
    boolean nullable,
    boolean primaryKey,
    int ordinalPosition
) {}
