package com.onelake.integration.service.impl;

import com.onelake.common.audit.AuditLogger;
import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.outbox.OutboxPublisher;
import com.onelake.common.system.repository.ProjectRepository;
import com.onelake.common.util.JsonUtil;
import com.onelake.integration.api.vo.ConnectivityResult;
import com.onelake.integration.api.vo.CreateDataSourceVO;
import com.onelake.integration.api.vo.ProbeDatabasesVO;
import com.onelake.integration.api.vo.TestDataSourceVO;
import com.onelake.integration.api.vo.UpdateDataSourceVO;
import com.onelake.integration.client.ConnectivityTester;
import com.onelake.integration.client.discovery.DatabaseDiscoveryClient;
import com.onelake.integration.domain.entity.DataSource;
import com.onelake.integration.domain.enums.DataSourceType;
import com.onelake.integration.domain.enums.EnvLevel;
import com.onelake.integration.domain.enums.Health;
import com.onelake.integration.domain.enums.NetworkMode;
import com.onelake.integration.dto.DataSourceDTO;
import com.onelake.integration.mapper.DataSourceMapper;
import com.onelake.integration.repository.DataSourceRepository;
import com.onelake.integration.repository.SyncTaskRepository;
import com.onelake.integration.service.validation.DataSourceConfigValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataSourceServiceImplTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private DataSourceRepository repo;
    private SyncTaskRepository taskRepo;
    private ConnectivityTester tester;
    private OutboxPublisher outbox;
    private AuditLogger audit;
    private ProjectRepository projectRepository;
    private DatabaseDiscoveryClient databaseDiscoveryClient;
    private DataSourceServiceImpl service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT_ID);
        repo = mock(DataSourceRepository.class);
        taskRepo = mock(SyncTaskRepository.class);
        tester = mock(ConnectivityTester.class);
        outbox = mock(OutboxPublisher.class);
        audit = mock(AuditLogger.class);
        projectRepository = mock(ProjectRepository.class);
        databaseDiscoveryClient = mock(DatabaseDiscoveryClient.class);
        DataSourceMapper mapper = Mappers.getMapper(DataSourceMapper.class);
        service = new DataSourceServiceImpl(
            repo,
            taskRepo,
            mapper,
            tester,
            outbox,
            audit,
            projectRepository,
            new DataSourceConfigValidator(),
            databaseDiscoveryClient
        );
        when(projectRepository.existsByTenantIdAndId(eq(TENANT_ID), any(UUID.class))).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void createPersistsDatasourceWithTenantAndPublishesAuditEvents() {
        CreateDataSourceVO vo = new CreateDataSourceVO(
            "orders-db",
            "postgres",
            Map.of("host", "db.internal", "port", 5432, "database", "orders", "username", "reader"),
            "secret-ref",
            "direct",
            "prod",
            UUID.randomUUID()
        );
        when(repo.findByTenantIdAndName(TENANT_ID, "orders-db")).thenReturn(Optional.empty());
        when(repo.save(any(DataSource.class))).thenAnswer(invocation -> {
            DataSource ds = invocation.getArgument(0);
            ds.setId(UUID.randomUUID());
            return ds;
        });

        DataSourceDTO dto = service.create(vo);

        assertThat(dto.tenantId()).isEqualTo(TENANT_ID);
        assertThat(dto.name()).isEqualTo("orders-db");
        assertThat(dto.health()).isEqualTo("UNKNOWN");
        verify(outbox).publish(eq("integration.datasource.created"), eq(dto.id().toString()), any(Map.class));
        verify(audit).auditCreate(eq("datasource"), eq(dto.id()), any(Map.class));
    }

    @Test
    void createRejectsDuplicateDatasourceNameWithinTenant() {
        when(repo.findByTenantIdAndName(TENANT_ID, "orders-db")).thenReturn(Optional.of(datasource(UUID.randomUUID())));

        CreateDataSourceVO vo = new CreateDataSourceVO(
            "orders-db",
            "postgres",
            Map.of("host", "db.internal", "port", 5432, "database", "orders", "username", "reader"),
            null,
            null,
            null,
            null
        );

        assertThatThrownBy(() -> service.create(vo))
            .isInstanceOf(BizException.class)
            .hasMessage("数据源名称已存在");
        verify(repo, never()).save(any());
    }

    @Test
    void listRequiresTenantContext() {
        TenantContext.clear();

        assertThatThrownBy(() -> service.list(null, null, null, null))
            .isInstanceOf(BizException.class)
            .hasMessage("租户上下文缺失");
    }

    @Test
    void listAppliesOptionalFiltersAndMapsResults() {
        DataSource ds = datasource(UUID.randomUUID());
        ds.setName("orders-prod");
        when(repo.findAll(any(Specification.class))).thenReturn(List.of(ds));

        List<DataSourceDTO> result = service.list("POSTGRES", "OK", "PROD", "orders");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("orders-prod");
        verify(repo).findAll(any(Specification.class));
    }

    @Test
    void listRejectsUnsupportedFilters() {
        assertThatThrownBy(() -> service.list("not-a-type", null, null, null))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("不支持的数据源类型");

        assertThatThrownBy(() -> service.list(null, "bad-health", null, null))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("不支持的健康状态");
    }

    @Test
    void updatePatchesMutableFields() {
        UUID id = UUID.randomUUID();
        DataSource ds = datasource(id);
        when(repo.findById(id)).thenReturn(Optional.of(ds));
        UUID projectId = UUID.randomUUID();

        DataSourceDTO dto = service.update(id, new UpdateDataSourceVO(
            "orders-dev",
            Map.of(
                "host", "dev-db",
                "port", 15432,
                "dbName", "orders_dev",
                "username", "reader",
                "networkAccessRef", "vpc-access-dev"
            ),
            "new-secret",
            "vpc",
            "dev",
            projectId
        ));

        assertThat(dto.name()).isEqualTo("orders-dev");
        assertThat(dto.host()).isEqualTo("dev-db");
        assertThat(dto.port()).isEqualTo(15432);
        assertThat(dto.networkMode()).isEqualTo("VPC");
        assertThat(dto.envLevel()).isEqualTo("DEV");
        assertThat(dto.projectId()).isEqualTo(projectId);
        verify(audit).auditUpdate("datasource", id, Map.of("fields", "patched"));
    }

    @Test
    void createRejectsJdbcWithoutDatabaseName() {
        CreateDataSourceVO vo = new CreateDataSourceVO(
            "orders-db",
            "mysql",
            Map.of("host", "db.internal", "port", 3306, "username", "reader"),
            null,
            "direct",
            null,
            null
        );

        assertThatThrownBy(() -> service.create(vo))
            .isInstanceOf(BizException.class)
            .hasMessage("缺少数据源配置字段: dbName/database");
        verify(repo, never()).save(any());
    }

    @Test
    void createRejectsVpcModeWithoutNetworkAccessReference() {
        CreateDataSourceVO vo = new CreateDataSourceVO(
            "orders-db",
            "mysql",
            Map.of("host", "db.internal", "port", 3306, "dbName", "orders", "username", "reader"),
            null,
            "vpc",
            null,
            null
        );

        assertThatThrownBy(() -> service.create(vo))
            .isInstanceOf(BizException.class)
            .hasMessage("缺少数据源配置字段: networkAccessRef");
        verify(repo, never()).save(any());
    }

    @Test
    void createRejectsSshTunnelWithoutAuthSecretReference() {
        CreateDataSourceVO vo = new CreateDataSourceVO(
            "orders-db",
            "mysql",
            Map.of(
                "host", "db.internal",
                "port", 3306,
                "dbName", "orders",
                "username", "reader",
                "sshHost", "bastion.internal",
                "sshPort", 22,
                "sshUsername", "jump"
            ),
            null,
            "ssh_tunnel",
            null,
            null
        );

        assertThatThrownBy(() -> service.create(vo))
            .isInstanceOf(BizException.class)
            .hasMessage("缺少数据源配置字段: sshPrivateKeyRef");
        verify(repo, never()).save(any());
    }

    @Test
    void probeDatabasesAllowsMissingDatabaseNameAndReturnsDiscoveredOptions() {
        ProbeDatabasesVO vo = new ProbeDatabasesVO(
            "mysql",
            Map.of("host", "db.internal", "port", 3306, "username", "reader"),
            "direct"
        );
        when(databaseDiscoveryClient.discover(DataSourceType.MYSQL, vo.config()))
            .thenReturn(List.of("orders", "inventory"));

        var result = service.probeDatabases(vo);

        assertThat(result.databases()).containsExactly("orders", "inventory");
        assertThat(result.manualAllowed()).isTrue();
        verify(databaseDiscoveryClient).discover(DataSourceType.MYSQL, vo.config());
    }

    @Test
    void probeDatabasesRejectsUnsupportedSourceType() {
        ProbeDatabasesVO vo = new ProbeDatabasesVO(
            "s3",
            Map.of("bucket", "raw-zone"),
            "direct"
        );

        assertThatThrownBy(() -> service.probeDatabases(vo))
            .isInstanceOf(BizException.class)
            .hasMessage("当前类型暂不支持库列表探查，请手动输入");
    }

    @Test
    void createRejectsInvalidTypeSpecificConfig() {
        CreateDataSourceVO vo = new CreateDataSourceVO(
            "orders-topic",
            "kafka",
            Map.of("topicPattern", "orders.*"),
            null,
            null,
            null,
            null
        );

        assertThatThrownBy(() -> service.create(vo))
            .isInstanceOf(BizException.class)
            .hasMessage("缺少数据源配置字段: bootstrapServers");
        verify(repo, never()).save(any());
    }

    @Test
    void createAcceptsS3ConfigWithCurrentTenantProject() {
        UUID projectId = UUID.randomUUID();
        CreateDataSourceVO vo = new CreateDataSourceVO(
            "raw-bucket",
            "s3",
            Map.of("endpoint", "http://minio:9000", "bucket", "raw-zone", "region", "cn-north-1"),
            null,
            null,
            null,
            projectId
        );
        when(repo.findByTenantIdAndName(TENANT_ID, "raw-bucket")).thenReturn(Optional.empty());
        when(projectRepository.existsByTenantIdAndId(TENANT_ID, projectId)).thenReturn(true);
        when(repo.save(any(DataSource.class))).thenAnswer(invocation -> {
            DataSource ds = invocation.getArgument(0);
            ds.setId(UUID.randomUUID());
            return ds;
        });

        DataSourceDTO dto = service.create(vo);

        assertThat(dto.type()).isEqualTo("S3");
        assertThat(dto.projectId()).isEqualTo(projectId);
        verify(repo).save(any(DataSource.class));
    }

    @Test
    void createRejectsProjectOutsideCurrentTenant() {
        UUID projectId = UUID.randomUUID();
        when(projectRepository.existsByTenantIdAndId(TENANT_ID, projectId)).thenReturn(false);
        CreateDataSourceVO vo = new CreateDataSourceVO(
            "orders-db",
            "postgres",
            Map.of("host", "db.internal", "port", 5432, "database", "orders", "username", "reader"),
            null,
            null,
            null,
            projectId
        );

        assertThatThrownBy(() -> service.create(vo))
            .isInstanceOf(BizException.class)
            .hasMessage("项目不存在或不属于当前租户");
        verify(repo, never()).save(any());
    }

    @Test
    void deleteRejectsReferencedDatasource() {
        UUID id = UUID.randomUUID();
        when(repo.findById(id)).thenReturn(Optional.of(datasource(id)));
        when(taskRepo.existsBySourceId(id)).thenReturn(true);

        assertThatThrownBy(() -> service.delete(id))
            .isInstanceOf(BizException.class)
            .hasMessage("数据源已被采集任务引用，不能删除");
        verify(repo, never()).delete(any(DataSource.class));
    }

    @Test
    void testConnectivityUpdatesHealthAndLastCheckTime() {
        UUID id = UUID.randomUUID();
        DataSource ds = datasource(id);
        ConnectivityResult result = new ConnectivityResult(
            true,
            null,
            "连通",
            12L,
            Instant.now(),
            Map.of("host", "db.internal")
        );
        when(repo.findById(id)).thenReturn(Optional.of(ds));
        when(tester.test(ds)).thenReturn(result);

        ConnectivityResult actual = service.testConnectivity(id);

        assertThat(actual.ok()).isTrue();
        assertThat(ds.getHealth()).isEqualTo(Health.OK);
        assertThat(ds.getLastCheckAt()).isNotNull();
        verify(audit).audit(eq("TEST"), eq("datasource"), eq(id.toString()), any(Map.class));
    }

    @Test
    void testConnectivityByConfigValidatesAndRunsWithoutPersistingDatasource() {
        TestDataSourceVO vo = new TestDataSourceVO(
            "mysql",
            Map.of("host", "db.internal", "port", 3306, "dbName", "orders", "username", "reader"),
            "direct"
        );
        ConnectivityResult result = new ConnectivityResult(
            true,
            null,
            "连通",
            9L,
            Instant.now(),
            Map.of("host", "db.internal")
        );
        ArgumentCaptor<DataSource> captor = ArgumentCaptor.forClass(DataSource.class);
        when(tester.test(any(DataSource.class))).thenReturn(result);

        ConnectivityResult actual = service.testConnectivity(vo);

        assertThat(actual.ok()).isTrue();
        verify(tester).test(captor.capture());
        assertThat(captor.getValue().getId()).isNull();
        assertThat(captor.getValue().getType()).isEqualTo(DataSourceType.MYSQL);
        assertThat(captor.getValue().getNetworkMode()).isEqualTo(NetworkMode.DIRECT);
        verify(repo, never()).save(any());
        verify(audit, never()).audit(eq("TEST"), eq("datasource"), any(), any(Map.class));
    }

    private DataSource datasource(UUID id) {
        DataSource ds = new DataSource();
        ds.setId(id);
        ds.setTenantId(TENANT_ID);
        ds.setName("orders-db");
        ds.setType(DataSourceType.POSTGRES);
        ds.setHealth(Health.OK);
        ds.setNetworkMode(NetworkMode.DIRECT);
        ds.setEnvLevel(EnvLevel.PROD);
        ds.setConfig(JsonUtil.toJson(Map.of(
            "host", "db.internal",
            "port", 5432,
            "database", "orders",
            "username", "reader"
        )));
        return ds;
    }
}
