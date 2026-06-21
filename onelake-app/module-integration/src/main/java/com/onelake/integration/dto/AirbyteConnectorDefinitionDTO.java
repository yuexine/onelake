package com.onelake.integration.dto;

public record AirbyteConnectorDefinitionDTO(
    String id,
    String name,
    String dockerRepository,
    String dockerImageTag,
    String type
) {}
