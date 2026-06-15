package com.onelake.integration.api.vo;

import java.util.List;

public record DatabaseProbeResult(
    List<String> databases,
    boolean manualAllowed,
    String message
) {}
