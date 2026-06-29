package com.onelake.modeling.dto;

import java.util.List;

public record CodebookRequest(
    String code,
    String name,
    String domain,
    String description,
    String noMatchPolicy,
    List<CodebookEntryDTO> entries,
    List<String> tags
) {}
