package com.onelake.integration.service;

import com.onelake.integration.api.vo.ConnectivityResult;
import com.onelake.integration.api.vo.CreateDataSourceVO;
import com.onelake.integration.api.vo.DatabaseProbeResult;
import com.onelake.integration.api.vo.ProbeDatabasesVO;
import com.onelake.integration.api.vo.UpdateDataSourceVO;
import com.onelake.integration.dto.DataSourceDTO;

import java.util.List;
import java.util.UUID;

public interface DataSourceService {

    DataSourceDTO create(CreateDataSourceVO vo);

    DataSourceDTO update(UUID id, UpdateDataSourceVO vo);

    void delete(UUID id);

    DataSourceDTO get(UUID id);

    List<DataSourceDTO> list(String type, String health, String envLevel, String keyword);

    ConnectivityResult testConnectivity(UUID id);

    DatabaseProbeResult probeDatabases(ProbeDatabasesVO vo);
}
