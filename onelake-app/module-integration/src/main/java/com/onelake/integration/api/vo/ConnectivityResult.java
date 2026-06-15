package com.onelake.integration.api.vo;

import java.time.Instant;
import java.util.Map;

public record ConnectivityResult(
    boolean ok,
    String errorCode,        // NET / AUTH / DRV / null
    String message,
    Long rttMillis,
    Instant checkedAt,
    Map<String, Object> diagnostics
) {}
