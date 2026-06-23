package com.onelake.modeling.dto;

import java.util.List;
import java.util.UUID;

public record BusinessTermRequest(
    String code,
    String name,
    UUID domainId,
    String definition,
    String caliberSql,
    List<String> synonyms,
    UUID ownerId,
    String ownerName,
    UUID stewardId,
    String sensitivityLevel,
    List<String> tags
) {}
