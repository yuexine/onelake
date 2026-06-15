package com.onelake.common.system.dto;

import java.util.UUID;

public record TenantOptionDTO(
    UUID id,
    String code,
    String name,
    String status
) {}
