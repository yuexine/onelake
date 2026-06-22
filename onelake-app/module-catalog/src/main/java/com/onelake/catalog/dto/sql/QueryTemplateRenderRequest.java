package com.onelake.catalog.dto.sql;

import java.util.Map;

public record QueryTemplateRenderRequest(
    Map<String, String> values
) {}
