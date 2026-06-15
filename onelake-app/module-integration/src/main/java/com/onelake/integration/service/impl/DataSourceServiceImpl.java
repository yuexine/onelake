package com.onelake.integration.service.impl;

import com.onelake.common.audit.AuditLogger;
import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.outbox.OutboxPublisher;
import com.onelake.integration.api.vo.ConnectivityResult;
import com.onelake.integration.api.vo.CreateDataSourceVO;
import com.onelake.integration.api.vo.UpdateDataSourceVO;
import com.onelake.integration.client.ConnectivityTester;
import com.onelake.integration.domain.entity.DataSource;
import com.onelake.integration.domain.enums.DataSourceType;
import com.onelake.integration.domain.enums.Health;
import com.onelake.integration.dto.DataSourceDTO;
import com.onelake.integration.mapper.DataSourceMapper;
import com.onelake.integration.repository.DataSourceRepository;
import com.onelake.integration.service.DataSourceService;
import lombok.RequiredArgsConstructor;
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
    private final DataSourceMapper mapper;
    private final ConnectivityTester tester;
    private final OutboxPublisher outbox;
    private final AuditLogger audit;

    @Override
    @Transactional
    public DataSourceDTO create(CreateDataSourceVO vo) {
        UUID tenantId = TenantContext.getTenantId();
        repo.findByTenantIdAndName(tenantId, vo.name()).ifPresent(d -> {
            throw new BizException(40901, "数据源名称已存在");
        });
        DataSource ds = mapper.toEntity(vo);
        ds.setTenantId(tenantId);
        ds.setHealth(Health.UNKNOWN);
        repo.save(ds);

        outbox.publish("integration.datasource.created", ds.getId().toString(),
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
        if (vo.config() != null) ds.setConfig(com.onelake.common.util.JsonUtil.toJson(vo.config()));
        if (vo.secretRef() != null) ds.setSecretRef(vo.secretRef());
        if (vo.networkMode() != null) {
            ds.setNetworkMode(com.onelake.integration.domain.enums.NetworkMode.valueOf(vo.networkMode().toUpperCase()));
        }
        if (vo.envLevel() != null) {
            ds.setEnvLevel(com.onelake.integration.domain.enums.EnvLevel.valueOf(vo.envLevel().toUpperCase()));
        }
        if (vo.projectId() != null) ds.setProjectId(vo.projectId());

        audit.auditUpdate("datasource", id, Map.of("fields", "patched"));
        return mapper.toDTO(ds);
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        DataSource ds = repo.findById(id)
            .orElseThrow(() -> new BizException(40400, "数据源不存在"));
        repo.delete(ds);
        audit.auditDelete("datasource", id);
    }

    @Override
    @Transactional(readOnly = true)
    public DataSourceDTO get(UUID id) {
        return mapper.toDTO(repo.findById(id)
            .orElseThrow(() -> new BizException(40400, "数据源不存在")));
    }

    @Override
    @Transactional(readOnly = true)
    public List<DataSourceDTO> list(String type) {
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new BizException(40100, "租户上下文缺失");
        }
        List<DataSource> all = type == null || type.isBlank()
            ? repo.findByTenantId(tenantId)
            : repo.findByTenantIdAndTypeIgnoreCase(tenantId, DataSourceType.valueOf(type.toUpperCase()));
        return all.stream().map(mapper::toDTO).toList();
    }

    @Override
    @Transactional
    public ConnectivityResult testConnectivity(UUID id) {
        DataSource ds = repo.findById(id)
            .orElseThrow(() -> new BizException(40400, "数据源不存在"));
        ConnectivityResult r = tester.test(ds);
        ds.setHealth(r.ok() ? Health.OK : Health.FAIL);
        ds.setLastCheckAt(Instant.now());
        audit.audit("TEST", "datasource", id.toString(),
            Map.of("ok", r.ok(), "errorCode", r.errorCode() == null ? "-" : r.errorCode()));
        return r;
    }
}
