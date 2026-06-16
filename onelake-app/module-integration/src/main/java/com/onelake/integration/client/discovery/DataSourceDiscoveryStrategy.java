package com.onelake.integration.client.discovery;

import com.onelake.integration.domain.enums.DataSourceType;
import com.onelake.integration.dto.DiscoveredColumnDTO;

import java.util.List;
import java.util.Map;

public interface DataSourceDiscoveryStrategy {

    DataSourceType type();

    List<String> discover(Map<String, Object> config);

    List<String> listSchemas(Map<String, Object> config);

    List<String> listTables(Map<String, Object> config, String schema);

    List<DiscoveredColumnDTO> describeTable(Map<String, Object> config, String objectName);
}
