package com.onelake.integration.mapper;

import com.onelake.integration.api.vo.CreateSyncTaskVO;
import com.onelake.integration.domain.entity.SyncTask;
import com.onelake.integration.domain.enums.SyncMode;
import com.onelake.integration.domain.enums.TaskStatus;
import com.onelake.integration.dto.SyncTaskDTO;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SyncTaskMapperTest {

    private final SyncTaskMapper mapper = Mappers.getMapper(SyncTaskMapper.class);

    @Test
    void toEntityNormalizesModeAndSerializesFieldMapping() {
        UUID sourceId = UUID.randomUUID();
        CreateSyncTaskVO vo = new CreateSyncTaskVO(
            sourceId,
            "orders-cdc",
            "cdc",
            "public.orders",
            "ods.orders",
            List.of(Map.of("source", "id", "target", "id")),
            "0 */5 * * * ?",
            1000,
            5,
            "airbyte-conn"
        );

        SyncTask entity = mapper.toEntity(vo);

        assertThat(entity.getSourceId()).isEqualTo(sourceId);
        assertThat(entity.getName()).isEqualTo("orders-cdc");
        assertThat(entity.getMode()).isEqualTo(SyncMode.CDC);
        assertThat(entity.getStatus()).isEqualTo(TaskStatus.DRAFT);
        assertThat(entity.getFieldMapping()).contains("\"source\":\"id\"");
    }

    @Test
    void toDtoIncludesSourceNameAndParsedFieldMapping() {
        UUID id = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        SyncTask task = new SyncTask();
        task.setId(id);
        task.setSourceId(sourceId);
        task.setName("orders-full");
        task.setMode(SyncMode.FULL);
        task.setTargetTable("ods.orders");
        task.setFieldMapping("[{\"source\":\"id\",\"target\":\"id\"}]");
        task.setScheduleCron("0 0 * * * ?");
        task.setRateLimit(500);
        task.setDirtyThreshold(3);
        task.setStatus(TaskStatus.ENABLED);
        task.setAirbyteConnectionId("conn-1");

        SyncTaskDTO dto = mapper.toDTO(task, "orders-db");

        assertThat(dto.id()).isEqualTo(id);
        assertThat(dto.sourceId()).isEqualTo(sourceId);
        assertThat(dto.sourceName()).isEqualTo("orders-db");
        assertThat(dto.mode()).isEqualTo("FULL");
        assertThat(dto.status()).isEqualTo("ENABLED");
        assertThat(dto.fieldMapping()).hasSize(1);
        assertThat(dto.fieldMapping().get(0)).containsEntry("target", "id");
    }

    @Test
    void invalidFieldMappingJsonFallsBackToEmptyList() {
        SyncTask task = new SyncTask();
        task.setSourceId(UUID.randomUUID());
        task.setName("bad-json");
        task.setMode(SyncMode.FILE);
        task.setTargetTable("ods.file");
        task.setStatus(TaskStatus.DRAFT);
        task.setFieldMapping("{bad-json");

        assertThat(mapper.toDTO(task).fieldMapping()).isEmpty();
    }

    @Test
    void invalidModeFailsFast() {
        CreateSyncTaskVO vo = new CreateSyncTaskVO(
            UUID.randomUUID(),
            "bad",
            "streaming",
            "public.bad",
            "ods.bad",
            null,
            null,
            null,
            null,
            null
        );

        assertThatThrownBy(() -> mapper.toEntity(vo))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
