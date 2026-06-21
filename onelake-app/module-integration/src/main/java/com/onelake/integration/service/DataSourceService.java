package com.onelake.integration.service;

import com.onelake.integration.api.vo.ConnectivityResult;
import com.onelake.integration.api.vo.CreateDataSourceVO;
import com.onelake.integration.api.vo.DatabaseProbeResult;
import com.onelake.integration.api.vo.ProbeDatabasesVO;
import com.onelake.integration.api.vo.TestDataSourceVO;
import com.onelake.integration.api.vo.UpdateDataSourceVO;
import com.onelake.integration.dto.DataSourceDTO;
import com.onelake.integration.dto.DiscoveredColumnDTO;
import com.onelake.integration.dto.AirbyteConnectorDefinitionDTO;
import com.onelake.integration.dto.AirbyteConnectorSpecDTO;

import java.util.List;
import java.util.UUID;

public interface DataSourceService {

    DataSourceDTO create(CreateDataSourceVO vo);

    DataSourceDTO update(UUID id, UpdateDataSourceVO vo);

    void delete(UUID id);

    DataSourceDTO get(UUID id);

    List<DataSourceDTO> list(String type, String health, String envLevel, String keyword);

    ConnectivityResult testConnectivity(UUID id);

    ConnectivityResult testConnectivity(TestDataSourceVO vo);

    DatabaseProbeResult probeDatabases(ProbeDatabasesVO vo);

    List<String> listSchemas(UUID id);

    List<String> listTables(UUID id, String schema);

    List<DiscoveredColumnDTO> describeTable(UUID id, String objectName);

    List<AirbyteConnectorDefinitionDTO> listAirbyteSourceDefinitions();

    List<AirbyteConnectorDefinitionDTO> listAirbyteDestinationDefinitions();

    AirbyteConnectorSpecDTO getAirbyteSourceDefinitionSpec(String definitionId);

    AirbyteConnectorSpecDTO getAirbyteDestinationDefinitionSpec(String definitionId);
}
