package com.onelake.modeling.event;

import com.onelake.common.context.TenantContext;
import com.onelake.common.outbox.DomainEvents;
import com.onelake.common.outbox.OutboxEvent;
import com.onelake.common.util.JsonUtil;
import com.onelake.modeling.domain.entity.DataModel;
import com.onelake.modeling.repository.DataModelRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class DwdSchemaDriftEventHandlerTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final DataModelRepository modelRepo = mock(DataModelRepository.class);
    private final DwdSchemaDriftEventHandler handler = new DwdSchemaDriftEventHandler(modelRepo);

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void marksValidatedDwdModelAsNeedsReviewWhenOdsSchemaDrifts() {
        UUID previousTenant = UUID.fromString("99999999-9999-9999-9999-999999999999");
        TenantContext.setTenantId(previousTenant);
        DataModel validated = model("VALIDATED");
        DataModel draft = model("DRAFT");
        when(modelRepo.findByTenantIdAndSourceFqnOrderByCreatedAtDesc(TENANT_ID, "ods.ods_codex_orders"))
            .thenReturn(List.of(validated, draft));

        handler.handle(event(Map.of(
            "targetTables", List.of("ods.ods_codex_orders"),
            "changes", List.of(Map.of("name", "memo", "changeType", "ADD"))
        )));

        assertThat(validated.getStatus()).isEqualTo("NEEDS_REVIEW");
        assertThat(draft.getStatus()).isEqualTo("DRAFT");
        assertThat(TenantContext.getTenantId()).isEqualTo(previousTenant);
        verify(modelRepo).save(validated);
    }

    @Test
    void skipsWhenNoOdsTargetTable() {
        handler.handle(event(Map.of("targetTables", List.of("public.orders"))));

        verifyNoMoreInteractions(modelRepo);
    }

    private OutboxEvent event(Map<String, Object> payload) {
        OutboxEvent event = new OutboxEvent();
        event.setId(UUID.randomUUID());
        event.setTenantId(TENANT_ID);
        event.setEventType(DomainEvents.INTEGRATION_SCHEMA_DRIFT);
        event.setAggregateId(UUID.randomUUID().toString());
        event.setPayload(JsonUtil.toJson(payload));
        return event;
    }

    private DataModel model(String status) {
        DataModel model = new DataModel();
        model.setId(UUID.randomUUID());
        model.setTenantId(TENANT_ID);
        model.setSourceFqn("ods.ods_codex_orders");
        model.setTargetFqn("dwd.dwd_trade_codex_orders_df");
        model.setStatus(status);
        return model;
    }
}
