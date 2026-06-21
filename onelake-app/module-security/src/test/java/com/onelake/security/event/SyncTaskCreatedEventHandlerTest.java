package com.onelake.security.event;

import com.onelake.common.outbox.DomainEvents;
import com.onelake.common.outbox.OutboxEvent;
import com.onelake.common.util.JsonUtil;
import com.onelake.security.service.PiiScanService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SyncTaskCreatedEventHandlerTest {

    @Test
    void handlesSyncTaskCreatedWithFieldMapping() {
        PiiScanService service = mock(PiiScanService.class);
        SyncTaskCreatedEventHandler handler = new SyncTaskCreatedEventHandler(service);
        UUID tenantId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        OutboxEvent event = new OutboxEvent();
        event.setId(UUID.randomUUID());
        event.setEventType(DomainEvents.INTEGRATION_SYNC_TASK_CREATED);
        event.setPayload(JsonUtil.toJson(Map.of(
            "tenantId", tenantId.toString(),
            "name", "customers-sync",
            "targetTable", "ods.customers",
            "fieldMapping", List.of(
                Map.of("source", "phone", "target", "phone_hash", "targetType", "STRING"),
                Map.of("source", "email", "target", "email_hash", "targetType", "STRING")
            )
        )));

        handler.handle(event);

        ArgumentCaptor<List<Map<String, Object>>> columnsCaptor = ArgumentCaptor.forClass(List.class);
        verify(service).enqueueScan(eq(tenantId), eq("ods.customers"), columnsCaptor.capture());
        assertThat(columnsCaptor.getValue()).hasSize(2);
        assertThat(columnsCaptor.getValue().get(0)).containsEntry("target", "phone_hash");
    }
}
