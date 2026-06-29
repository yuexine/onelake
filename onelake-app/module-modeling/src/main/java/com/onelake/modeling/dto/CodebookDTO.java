package com.onelake.modeling.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CodebookDTO(
    UUID id,
    String code,
    String name,
    String domain,
    String description,
    String status,
    String latestVersion,
    String noMatchPolicy,
    List<CodebookEntryDTO> entries,
    List<String> tags,
    Instant createdAt,
    Instant updatedAt,
    Instant publishedAt
) {}
