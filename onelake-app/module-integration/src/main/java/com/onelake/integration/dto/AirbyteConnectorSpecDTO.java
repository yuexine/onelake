package com.onelake.integration.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record AirbyteConnectorSpecDTO(
    String definitionId,
    String type,
    String documentationUrl,
    JsonNode connectionSpecification
) {}
