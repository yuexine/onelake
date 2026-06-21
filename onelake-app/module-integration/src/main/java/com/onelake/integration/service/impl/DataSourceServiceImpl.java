package com.onelake.integration.service.impl;

import com.onelake.common.audit.AuditLogger;
import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.outbox.DomainEvents;
import com.onelake.common.outbox.OutboxPublisher;
import com.onelake.common.system.repository.ProjectRepository;
import com.onelake.integration.api.vo.DatabaseProbeResult;
import com.onelake.integration.api.vo.ConnectivityResult;
import com.onelake.integration.api.vo.CreateDataSourceVO;
import com.onelake.integration.api.vo.ProbeDatabasesVO;
import com.onelake.integration.api.vo.TestDataSourceVO;
import com.onelake.integration.api.vo.UpdateDataSourceVO;
import com.onelake.integration.client.discovery.DatabaseDiscoveryClient;
import com.onelake.integration.client.ConnectivityTester;
import com.onelake.integration.client.AirbyteSyncDriver;
import com.onelake.integration.domain.entity.DataSource;
import com.onelake.integration.domain.enums.DataSourceType;
import com.onelake.integration.domain.enums.NetworkMode;
import com.onelake.integration.domain.enums.Health;
import com.onelake.integration.dto.DataSourceDTO;
import com.onelake.integration.dto.DiscoveredColumnDTO;
import com.onelake.integration.dto.AirbyteConnectorDefinitionDTO;
import com.onelake.integration.dto.AirbyteConnectorSpecDTO;
import com.onelake.integration.mapper.DataSourceMapper;
import com.onelake.integration.repository.DataSourceRepository;
import com.onelake.integration.repository.SyncTaskRepository;
import com.onelake.integration.service.DataSourceService;
import com.onelake.integration.service.validation.DataSourceConfigValidator;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 数据源服务（对应《技术初始化文档》§6.10 完整分层实现）。
 * - 事务边界在 service；
 * - 密级安全：不暴露 config 中的密码字段到 DTO；
 * - 与业务写同事务落 Outbox，保证最终一致。
 */
@Service
@RequiredArgsConstructor
public class DataSourceServiceImpl implements DataSourceService {

    private final DataSourceRepository repo;
    private final SyncTaskRepository taskRepo;
    private final DataSourceMapper mapper;
    private final ConnectivityTester tester;
    private final OutboxPublisher outbox;
    private final AuditLogger audit;
    private final ProjectRepository projectRepository;
    private final DataSourceConfigValidator configValidator;
    private final DatabaseDiscoveryClient databaseDiscoveryClient;
    private final AirbyteSyncDriver airbyte;

    @Override
    @Transactional
    public DataSourceDTO create(CreateDataSourceVO vo) {
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new BizException(40100, "租户上下文缺失");
        }
        DataSourceType parsedType = configValidator.parseType(vo.type());
        NetworkMode parsedNetworkMode = configValidator.parseNetworkMode(vo.networkMode());
        configValidator.validate(parsedType, parsedNetworkMode, vo.config());
        validateProject(tenantId, vo.projectId());
        repo.findByTenantIdAndName(tenantId, vo.name()).ifPresent(d -> {
            throw new BizException(40901, "数据源名称已存在");
        });
        DataSource ds = mapper.toEntity(vo);
        ds.setTenantId(tenantId);
        ds.setHealth(Health.UNKNOWN);
        repo.save(ds);

        outbox.publish(DomainEvents.INTEGRATION_DATASOURCE_CREATED, ds.getId().toString(),
            Map.of("type", ds.getType(), "name", ds.getName()));
        audit.auditCreate("datasource", ds.getId(),
            Map.of("name", ds.getName(), "type", ds.getType()));
        return mapper.toDTO(ds);
    }

    @Override
    @Transactional
    public DataSourceDTO update(UUID id, UpdateDataSourceVO vo) {
        DataSource ds = repo.findById(id)
            .orElseThrow(() -> new BizException(40400, "数据源不存在"));
        if (vo.name() != null) ds.setName(vo.name());
        NetworkMode patchedNetworkMode = ds.getNetworkMode();
        if (vo.networkMode() != null) {
            patchedNetworkMode = configValidator.parseNetworkMode(vo.networkMode());
        }
        if (vo.config() != null) {
            configValidator.validate(ds.getType(), patchedNetworkMode, vo.config());
            ds.setConfig(com.onelake.common.util.JsonUtil.toJson(vo.config()));
        }
        if (vo.secretRef() != null) ds.setSecretRef(vo.secretRef());
        if (vo.networkMode() != null) ds.setNetworkMode(patchedNetworkMode);
        if (vo.envLevel() != null) {
            ds.setEnvLevel(com.onelake.integration.domain.enums.EnvLevel.valueOf(vo.envLevel().toUpperCase()));
        }
        if (vo.projectId() != null) {
            validateProject(ds.getTenantId(), vo.projectId());
            ds.setProjectId(vo.projectId());
        }

        audit.auditUpdate("datasource", id, Map.of("fields", "patched"));
        outbox.publish(DomainEvents.INTEGRATION_DATASOURCE_UPDATED, id.toString(),
            Map.of("name", ds.getName(), "type", String.valueOf(ds.getType())));
        return mapper.toDTO(ds);
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        DataSource ds = repo.findById(id)
            .orElseThrow(() -> new BizException(40400, "数据源不存在"));
        if (taskRepo.existsBySourceId(id)) {
            throw new BizException(40902, "数据源已被采集任务引用，不能删除");
        }
        repo.delete(ds);
        audit.auditDelete("datasource", id);
        outbox.publish(DomainEvents.INTEGRATION_DATASOURCE_DELETED, id.toString(),
            Map.of("name", ds.getName(), "type", String.valueOf(ds.getType())));
    }

    @Override
    @Transactional(readOnly = true)
    public DataSourceDTO get(UUID id) {
        return mapper.toDTO(repo.findById(id)
            .orElseThrow(() -> new BizException(40400, "数据源不存在")));
    }

    @Override
    @Transactional(readOnly = true)
    public List<DataSourceDTO> list(String type, String health, String envLevel, String keyword) {
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new BizException(40100, "租户上下文缺失");
        }
        Specification<DataSource> spec = (root, query, cb) -> cb.equal(root.get("tenantId"), tenantId);
        if (type != null && !type.isBlank()) {
            DataSourceType parsedType = parseDataSourceType(type);
            spec = spec.and((root, query, cb) -> cb.equal(root.get("type"), parsedType));
        }
        if (health != null && !health.isBlank()) {
            Health parsedHealth = parseHealth(health);
            spec = spec.and((root, query, cb) -> cb.equal(root.get("health"), parsedHealth));
        }
        if (envLevel != null && !envLevel.isBlank()) {
            com.onelake.integration.domain.enums.EnvLevel parsedEnv =
                com.onelake.integration.domain.enums.EnvLevel.valueOf(envLevel.toUpperCase());
            spec = spec.and((root, query, cb) -> cb.equal(root.get("envLevel"), parsedEnv));
        }
        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.toLowerCase() + "%";
            spec = spec.and((root, query, cb) -> cb.like(cb.lower(root.get("name")), like));
        }
        return repo.findAll(spec).stream().map(mapper::toDTO).toList();
    }

    @Override
    @Transactional
    public ConnectivityResult testConnectivity(UUID id) {
        DataSource ds = repo.findById(id)
            .orElseThrow(() -> new BizException(40400, "数据源不存在"));
        Health previousHealth = ds.getHealth();
        ConnectivityResult r = tester.test(ds);
        ds.setHealth(r.ok() ? Health.OK : Health.FAIL);
        ds.setLastCheckAt(Instant.now());
        audit.audit("TEST", "datasource", id.toString(),
            Map.of("ok", r.ok(), "errorCode", r.errorCode() == null ? "-" : r.errorCode()));
        // 健康状态变化时发事件（catalog / monitor 消费）
        if (previousHealth != ds.getHealth()) {
            outbox.publish(DomainEvents.INTEGRATION_DATASOURCE_HEALTH_CHANGED, id.toString(),
                Map.of("previous", String.valueOf(previousHealth),
                       "current", String.valueOf(ds.getHealth()),
                       "ok", r.ok()));
        }
        return r;
    }

    @Override
    @Transactional(readOnly = true)
    public ConnectivityResult testConnectivity(TestDataSourceVO vo) {
        DataSourceType parsedType = configValidator.parseType(vo.type());
        NetworkMode parsedNetworkMode = configValidator.parseNetworkMode(vo.networkMode());
        configValidator.validate(parsedType, parsedNetworkMode, vo.config());

        DataSource ds = new DataSource();
        ds.setType(parsedType);
        ds.setNetworkMode(parsedNetworkMode);
        ds.setConfig(com.onelake.common.util.JsonUtil.toJson(vo.config()));
        return tester.test(ds);
    }

    @Override
    @Transactional(readOnly = true)
    public DatabaseProbeResult probeDatabases(ProbeDatabasesVO vo) {
        DataSourceType parsedType = configValidator.parseType(vo.type());
        NetworkMode parsedNetworkMode = configValidator.parseNetworkMode(vo.networkMode());
        configValidator.validateDatabaseProbe(parsedType, parsedNetworkMode, vo.config());
        List<String> databases = databaseDiscoveryClient.discover(parsedType, vo.config());
        return new DatabaseProbeResult(databases, true, databases.isEmpty() ? "未发现可选数据库，可手动输入" : "ok");
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> listSchemas(UUID id) {
        DataSource ds = loadDatasourceForCurrentTenant(id);
        return databaseDiscoveryClient.listSchemas(ds.getType(), configMap(ds));
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> listTables(UUID id, String schema) {
        DataSource ds = loadDatasourceForCurrentTenant(id);
        return databaseDiscoveryClient.listTables(ds.getType(), configMap(ds), schema);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DiscoveredColumnDTO> describeTable(UUID id, String objectName) {
        DataSource ds = loadDatasourceForCurrentTenant(id);
        return databaseDiscoveryClient.describeTable(ds.getType(), configMap(ds), objectName);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AirbyteConnectorDefinitionDTO> listAirbyteSourceDefinitions() {
        return airbyte.listSourceDefinitions();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AirbyteConnectorDefinitionDTO> listAirbyteDestinationDefinitions() {
        return airbyte.listDestinationDefinitions();
    }

    @Override
    @Transactional(readOnly = true)
    public AirbyteConnectorSpecDTO getAirbyteSourceDefinitionSpec(String definitionId) {
        return airbyte.getSourceDefinitionSpec(definitionId);
    }

    @Override
    @Transactional(readOnly = true)
    public AirbyteConnectorSpecDTO getAirbyteDestinationDefinitionSpec(String definitionId) {
        return airbyte.getDestinationDefinitionSpec(definitionId);
    }

    private DataSourceType parseDataSourceType(String type) {
        try {
            return DataSourceType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BizException(40011, "不支持的数据源类型: " + type);
        }
    }

    private Health parseHealth(String health) {
        try {
            return Health.valueOf(health.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BizException(40012, "不支持的健康状态: " + health);
        }
    }

    private void validateProject(UUID tenantId, UUID projectId) {
        if (projectId == null) {
            return;
        }
        if (!projectRepository.existsByTenantIdAndId(tenantId, projectId)) {
            throw new BizException(40018, "项目不存在或不属于当前租户");
        }
    }

    private DataSource loadDatasourceForCurrentTenant(UUID id) {
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new BizException(40100, "租户上下文缺失");
        }
        DataSource ds = repo.findById(id)
            .orElseThrow(() -> new BizException(40400, "数据源不存在"));
        if (!tenantId.equals(ds.getTenantId())) {
            throw new BizException(40400, "数据源不存在");
        }
        return ds;
    }

    private Map<String, Object> configMap(DataSource ds) {
        try {
            return com.onelake.common.util.JsonUtil.mapper().readValue(
                ds.getConfig(),
                new TypeReference<Map<String, Object>>() {}
            );
        } catch (Exception e) {
            throw new BizException(50001, "数据源配置解析失败");
        }
    }
}
