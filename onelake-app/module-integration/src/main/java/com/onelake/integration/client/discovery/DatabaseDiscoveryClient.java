package com.onelake.integration.client.discovery;

import com.onelake.common.exception.BizException;
import com.onelake.integration.domain.enums.DataSourceType;
import com.onelake.integration.dto.DiscoveredColumnDTO;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class DatabaseDiscoveryClient {

    private final Map<DataSourceType, DataSourceDiscoveryStrategy> strategies;

    public DatabaseDiscoveryClient(List<DataSourceDiscoveryStrategy> strategies) {
        EnumMap<DataSourceType, DataSourceDiscoveryStrategy> indexed = new EnumMap<>(DataSourceType.class);
        for (DataSourceDiscoveryStrategy strategy : strategies) {
            DataSourceDiscoveryStrategy previous = indexed.put(strategy.type(), strategy);
            if (previous != null) {
                throw new IllegalStateException("Duplicate discovery strategy for " + strategy.type());
            }
        }
        this.strategies = Collections.unmodifiableMap(indexed);
    }

    public List<String> discover(DataSourceType type, Map<String, Object> config) {
        return strategy(type, 40024, "当前类型暂不支持库列表探查，请手动输入").discover(config);
    }

    public List<String> listSchemas(DataSourceType type, Map<String, Object> config) {
        return strategy(type, 40026, "当前类型暂不支持 schema 探查").listSchemas(config);
    }

    public List<String> listTables(DataSourceType type, Map<String, Object> config, String schema) {
        return strategy(type, 40027, "当前类型暂不支持表探查").listTables(config, schema);
    }

    public List<DiscoveredColumnDTO> describeTable(DataSourceType type, Map<String, Object> config, String objectName) {
        return strategy(type, 40028, "当前类型暂不支持字段探查").describeTable(config, objectName);
    }

    private DataSourceDiscoveryStrategy strategy(DataSourceType type, int errorCode, String errorMessage) {
        DataSourceDiscoveryStrategy strategy = strategies.get(type);
        if (strategy == null) {
            throw new BizException(errorCode, errorMessage);
        }
        return strategy;
    }
}
