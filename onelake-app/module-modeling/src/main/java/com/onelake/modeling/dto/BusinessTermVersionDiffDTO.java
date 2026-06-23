package com.onelake.modeling.dto;

import java.util.List;
import java.util.UUID;

public record BusinessTermVersionDiffDTO(
    UUID termId,
    Integer fromVersion,
    Integer toVersion,
    List<FieldChangeDTO> changes
) {
    public record FieldChangeDTO(
        String field,
        Object before,
        Object after
    ) {}
}
