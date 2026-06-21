package com.onelake.integration.mapper;

import com.onelake.integration.api.vo.CreateSyncTaskVO;
import com.onelake.integration.domain.entity.SyncTask;
import com.onelake.integration.domain.enums.SyncMode;
import com.onelake.integration.domain.enums.TaskStatus;
import com.onelake.integration.dto.SyncTaskDTO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.List;
import java.util.Map;

@Mapper(componentModel = "spring")
public interface SyncTaskMapper {

    @Mapping(target = "fieldMapping", expression = "java(com.onelake.common.util.JsonUtil.toJson(vo.fieldMapping()))")
    @Mapping(target = "mode", source = "mode", qualifiedByName = "toMode")
    @Mapping(target = "status", expression = "java(com.onelake.integration.domain.enums.TaskStatus.DRAFT)")
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    SyncTask toEntity(CreateSyncTaskVO vo);

    default SyncTaskDTO toDTO(SyncTask entity) {
        return toDTO(entity, null);
    }

    default SyncTaskDTO toDTO(SyncTask entity, String sourceName) {
        if (entity == null) return null;
        return new SyncTaskDTO(
            entity.getId(),
            entity.getSourceId(),
            sourceName,
            entity.getName(),
            fromMode(entity.getMode()),
            entity.getSourceTable(),
            entity.getTargetTable(),
            fieldMapping(entity.getFieldMapping()),
            entity.getScheduleCron(),
            entity.getRateLimit(),
            entity.getDirtyThreshold(),
            fromStatus(entity.getStatus()),
            entity.getAirbyteConnectionId(),
            entity.getCreatedAt()
        );
    }

    @Named("toMode")
    default SyncMode toMode(String m) {
        return m == null ? null : SyncMode.valueOf(m.toUpperCase());
    }

    @Named("fromMode")
    default String fromMode(SyncMode m) {
        return m == null ? null : m.name();
    }

    @Named("fromStatus")
    default String fromStatus(TaskStatus s) {
        return s == null ? null : s.name();
    }

    @SuppressWarnings("unchecked")
    default List<Map<String, Object>> fieldMapping(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return com.onelake.common.util.JsonUtil.mapper().readValue(
                json,
                com.onelake.common.util.JsonUtil.mapper().getTypeFactory()
                    .constructCollectionType(List.class, Map.class)
            );
        } catch (Exception e) {
            return List.of();
        }
    }
}
