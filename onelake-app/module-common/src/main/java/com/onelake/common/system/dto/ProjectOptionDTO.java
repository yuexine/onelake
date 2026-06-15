package com.onelake.common.system.dto;

import java.util.UUID;

public record ProjectOptionDTO(
    UUID id,
    UUID tenantId,
    String code,
    String name
) {}
