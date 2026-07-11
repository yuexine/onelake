package com.onelake.orchestration.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

/** 两个不可变 Pipeline 快照之间的结构化差异。 */
public record PipelineVersionDiffDTO(
        UUID dagId,
        Integer fromVersion,
        Integer toVersion,
        CollectionDiff tasks,
        CollectionDiff edges,
        CollectionDiff params
) {
    /** 同一类快照对象的新增、删除和字段级修改。 */
    public record CollectionDiff(
            List<ItemDiff> added,
            List<ItemDiff> removed,
            List<ItemDiff> changed
    ) {}

    /** 单个结构对象的差异；新增时 before 为空，删除时 after 为空。 */
    public record ItemDiff(
            String key,
            JsonNode before,
            JsonNode after,
            List<FieldChange> fields
    ) {}

    /** 修改对象的单个字段差异。 */
    public record FieldChange(
            String field,
            JsonNode before,
            JsonNode after
    ) {}
}
