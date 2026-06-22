package com.onelake.catalog.dto.sql;

public record SqlEstimateDTO(
    String engine,
    Long estimatedScanBytes,
    boolean thresholdExceeded,
    String message,
    String routeReason
) {}
