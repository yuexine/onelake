package com.onelake.integration.mapper;

import com.onelake.integration.api.vo.CreateDataSourceVO;
import com.onelake.integration.domain.entity.DataSource;
import com.onelake.integration.domain.enums.DataSourceType;
import com.onelake.integration.domain.enums.EnvLevel;
import com.onelake.integration.domain.enums.NetworkMode;
import com.onelake.integration.dto.DataSourceDTO;
import com.fasterxml.jackson.databind.JsonNode;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

@Mapper(componentModel = "spring")
public interface DataSourceMapper {

    @Mapping(target = "config", expression = "java(com.onelake.common.util.JsonUtil.toJson(vo.config()))")
    @Mapping(target = "type", source = "type", qualifiedByName = "toType")
    @Mapping(target = "networkMode", source = "networkMode", qualifiedByName = "toNetworkMode")
    @Mapping(target = "envLevel", source = "envLevel", qualifiedByName = "toEnvLevel")
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "health", ignore = true)
    @Mapping(target = "lastCheckAt", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    DataSource toEntity(CreateDataSourceVO vo);

    @Mapping(target = "type", source = "type", qualifiedByName = "fromType")
    @Mapping(target = "health", source = "health", qualifiedByName = "fromHealth")
    @Mapping(target = "networkMode", source = "networkMode", qualifiedByName = "fromNetworkMode")
    @Mapping(target = "envLevel", source = "envLevel", qualifiedByName = "fromEnvLevel")
    @Mapping(target = "host", expression = "java(configText(entity, \"host\"))")
    @Mapping(target = "port", expression = "java(configInt(entity, \"port\"))")
    @Mapping(target = "dbName", expression = "java(configText(entity, \"dbName\", \"database\"))")
    @Mapping(target = "username", expression = "java(configText(entity, \"username\"))")
    @Mapping(target = "rttMs", ignore = true)
    DataSourceDTO toDTO(DataSource entity);

    @Named("toType")
    default DataSourceType toType(String t) {
        return t == null ? null : DataSourceType.valueOf(t.toUpperCase());
    }

    @Named("fromType")
    default String fromType(DataSourceType t) {
        return t == null ? null : t.name();
    }

    @Named("toNetworkMode")
    default NetworkMode toNetworkMode(String n) {
        return n == null ? NetworkMode.DIRECT : NetworkMode.valueOf(n.toUpperCase());
    }

    @Named("fromNetworkMode")
    default String fromNetworkMode(NetworkMode n) {
        return n == null ? null : n.name();
    }

    @Named("toEnvLevel")
    default EnvLevel toEnvLevel(String e) {
        return e == null ? EnvLevel.PROD : EnvLevel.valueOf(e.toUpperCase());
    }

    @Named("fromEnvLevel")
    default String fromEnvLevel(EnvLevel e) {
        return e == null ? null : e.name();
    }

    @Named("fromHealth")
    default String fromHealth(com.onelake.integration.domain.enums.Health h) {
        return h == null ? null : h.name();
    }

    default String configText(DataSource entity, String key) {
        return configText(entity, key, null);
    }

    default String configText(DataSource entity, String key, String fallbackKey) {
        JsonNode cfg = parseConfig(entity);
        if (cfg == null) return null;
        String value = cfg.path(key).asText(null);
        if ((value == null || value.isBlank()) && fallbackKey != null) {
            value = cfg.path(fallbackKey).asText(null);
        }
        return value;
    }

    default Integer configInt(DataSource entity, String key) {
        JsonNode cfg = parseConfig(entity);
        if (cfg == null || !cfg.hasNonNull(key)) return null;
        return cfg.path(key).asInt();
    }

    default JsonNode parseConfig(DataSource entity) {
        if (entity == null || entity.getConfig() == null || entity.getConfig().isBlank()) {
            return null;
        }
        return com.onelake.common.util.JsonUtil.parse(entity.getConfig());
    }
}
