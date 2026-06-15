package com.onelake.integration.mapper;

import com.onelake.common.util.JsonUtil;
import com.onelake.integration.api.vo.CreateDataSourceVO;
import com.onelake.integration.domain.entity.DataSource;
import com.onelake.integration.domain.enums.DataSourceType;
import com.onelake.integration.domain.enums.EnvLevel;
import com.onelake.integration.domain.enums.Health;
import com.onelake.integration.domain.enums.NetworkMode;
import com.onelake.integration.dto.DataSourceDTO;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataSourceMapperTest {

    private final DataSourceMapper mapper = Mappers.getMapper(DataSourceMapper.class);

    @Test
    void toEntityNormalizesEnumsAndSerializesConfig() {
        CreateDataSourceVO vo = new CreateDataSourceVO(
            "orders-db",
            "postgres",
            Map.of("host", "127.0.0.1", "port", 5432, "database", "orders", "password", "secret"),
            "secret-ref",
            "ssh_tunnel",
            "dev",
            UUID.randomUUID()
        );

        DataSource entity = mapper.toEntity(vo);

        assertThat(entity.getName()).isEqualTo("orders-db");
        assertThat(entity.getType()).isEqualTo(DataSourceType.POSTGRES);
        assertThat(entity.getNetworkMode()).isEqualTo(NetworkMode.SSH_TUNNEL);
        assertThat(entity.getEnvLevel()).isEqualTo(EnvLevel.DEV);
        assertThat(JsonUtil.parse(entity.getConfig()).path("database").asText()).isEqualTo("orders");
        assertThat(entity.getId()).isNull();
        assertThat(entity.getTenantId()).isNull();
    }

    @Test
    void toEntityAppliesDefaultNetworkAndEnvironment() {
        CreateDataSourceVO vo = new CreateDataSourceVO(
            "s3-raw",
            "s3",
            Map.of("bucket", "raw"),
            null,
            null,
            null,
            null
        );

        DataSource entity = mapper.toEntity(vo);

        assertThat(entity.getType()).isEqualTo(DataSourceType.S3);
        assertThat(entity.getNetworkMode()).isEqualTo(NetworkMode.DIRECT);
        assertThat(entity.getEnvLevel()).isEqualTo(EnvLevel.PROD);
    }

    @Test
    void toDtoExtractsSafeConnectionFieldsFromConfig() {
        UUID id = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        DataSource entity = new DataSource();
        entity.setId(id);
        entity.setTenantId(tenantId);
        entity.setName("orders-db");
        entity.setType(DataSourceType.POSTGRES);
        entity.setConfig(JsonUtil.toJson(Map.of(
            "host", "db.internal",
            "port", 5432,
            "database", "orders",
            "username", "reader",
            "password", "must-not-leak"
        )));
        entity.setHealth(Health.OK);
        entity.setNetworkMode(NetworkMode.VPC);
        entity.setEnvLevel(EnvLevel.TEST);
        entity.setSecretRef("security.secret/orders");

        DataSourceDTO dto = mapper.toDTO(entity);

        assertThat(dto.id()).isEqualTo(id);
        assertThat(dto.tenantId()).isEqualTo(tenantId);
        assertThat(dto.type()).isEqualTo("POSTGRES");
        assertThat(dto.host()).isEqualTo("db.internal");
        assertThat(dto.port()).isEqualTo(5432);
        assertThat(dto.dbName()).isEqualTo("orders");
        assertThat(dto.username()).isEqualTo("reader");
        assertThat(dto.health()).isEqualTo("OK");
        assertThat(dto.networkMode()).isEqualTo("VPC");
        assertThat(dto.envLevel()).isEqualTo("TEST");
        assertThat(dto.secretRef()).isEqualTo("security.secret/orders");
    }

    @Test
    void invalidTypeFailsFast() {
        CreateDataSourceVO vo = new CreateDataSourceVO(
            "bad",
            "unknown",
            Map.of("host", "localhost"),
            null,
            null,
            null,
            null
        );

        assertThatThrownBy(() -> mapper.toEntity(vo))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
