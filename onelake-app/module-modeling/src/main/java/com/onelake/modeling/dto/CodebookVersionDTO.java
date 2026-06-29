package com.onelake.modeling.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CodebookVersionDTO(
    UUID id,
    UUID codebookId,
    String version,
    List<CodebookEntryDTO> entries,
    String changeReason,
    UUID publishedBy,
    Instant createdAt
) {}
