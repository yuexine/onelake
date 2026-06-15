package com.onelake.common.system.dto;

import java.util.List;
import java.util.UUID;

public record SystemContextDTO(
    TenantOptionDTO tenant,
    List<ProjectOptionDTO> projects,
    UUID userId,
    String username,
    List<String> roles
) {}
