package com.onelake.modeling.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record BusinessTermDTO(
    UUID id,
    String code,
    String name,
    UUID domainId,
    String domainName,
    String definition,
    String caliberSql,
    List<String> synonyms,
    UUID ownerId,
    String ownerName,
    UUID stewardId,
    String status,
    Integer version,
    String sensitivityLevel,
    List<String> tags,
    Instant createdAt,
    Instant updatedAt,
    Instant approvedAt,
    long bindingCount,
    List<BusinessTermBindingDTO> bindings
) {}
