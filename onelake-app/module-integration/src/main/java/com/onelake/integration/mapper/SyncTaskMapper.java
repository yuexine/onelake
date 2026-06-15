package com.onelake.integration.mapper;

import com.onelake.integration.api.vo.CreateSyncTaskVO;
import com.onelake.integration.domain.entity.SyncTask;
import com.onelake.integration.domain.enums.SyncMode;
import com.onelake.integration.domain.enums.TaskStatus;
import com.onelake.integration.dto.SyncTaskDTO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

@Mapper(componentModel = "spring")
public interface SyncTaskMapper {

    @Mapping(target = "fieldMapping", expression = "java(com.onelake.common.util.JsonUtil.toJson(vo.fieldMapping()))")
    @Mapping(target = "mode", source = "mode", qualifiedByName = "toMode")
    @Mapping(target = "status", expression = "java(com.onelake.integration.domain.enums.TaskStatus.DRAFT)")
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "airbyteConnectionId", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    SyncTask toEntity(CreateSyncTaskVO vo);

    @Mapping(target = "mode", source = "mode", qualifiedByName = "fromMode")
    @Mapping(target = "status", source = "status", qualifiedByName = "fromStatus")
    SyncTaskDTO toDTO(SyncTask entity);

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
}
