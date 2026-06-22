package com.onelake.modeling.event;

import com.onelake.common.context.TenantContext;
import com.onelake.common.outbox.DomainEvents;
import com.onelake.common.outbox.OutboxEvent;
import com.onelake.common.util.JsonUtil;
import com.onelake.modeling.domain.entity.DataModel;
import com.onelake.modeling.dto.DwdModelRunRequest;
import com.onelake.modeling.repository.DataModelRepository;
import com.onelake.modeling.repository.DataModelRunRepository;
import com.onelake.modeling.service.DwdModelService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DwdOdsLoadedEventHandlerTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MODEL_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SYNC_RUN_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private DataModelRepository modelRepo;
    private DataModelRunRepository runRepo;
    private DwdModelService dwdModelService;
    private DwdOdsLoadedEventHandler handler;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        modelRepo = mock(DataModelRepository.class);
        runRepo = mock(DataModelRunRepository.class);
        dwdModelService = mock(DwdModelService.class);
        handler = new DwdOdsLoadedEventHandler(modelRepo, runRepo, dwdModelService);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void triggersValidatedDwdModelForOdsLoadedEvent() {
        when(modelRepo.findByTenantIdAndSourceFqnOrderByCreatedAtDesc(TENANT_ID, "ods.ods_codex_orders"))
            .thenReturn(List.of(model("VALIDATED")));

        handler.handle(event("ods.ods_codex_orders"));

        ArgumentCaptor<DwdModelRunRequest> requestCaptor = ArgumentCaptor.forClass(DwdModelRunRequest.class);
        verify(dwdModelService).run(eq(MODEL_ID), requestCaptor.capture());
        assertThat(requestCaptor.getValue().triggerType()).isEqualTo("ODS_EVENT");
        assertThat(requestCaptor.getValue().sourceIntegrationRunId()).isEqualTo(SYNC_RUN_ID);
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    void skipsDuplicateIntegrationRun() {
        when(modelRepo.findByTenantIdAndSourceFqnOrderByCreatedAtDesc(TENANT_ID, "ods.ods_codex_orders"))
            .thenReturn(List.of(model("VALIDATED")));
        when(runRepo.existsByModelIdAndSourceIntegrationRunId(MODEL_ID, SYNC_RUN_ID)).thenReturn(true);

        handler.handle(event("ods.ods_codex_orders"));

        verify(dwdModelService, never()).run(eq(MODEL_ID), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void skipsWhenModelHasActiveRun() {
        when(modelRepo.findByTenantIdAndSourceFqnOrderByCreatedAtDesc(TENANT_ID, "ods.ods_codex_orders"))
            .thenReturn(List.of(model("VALIDATED")));
        when(runRepo.existsByModelIdAndStatusIn(eq(MODEL_ID), anyCollection())).thenReturn(true);

        handler.handle(event("ods.ods_codex_orders"));

        verify(dwdModelService, never()).run(eq(MODEL_ID), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void skipsNonOdsTable() {
        handler.handle(event("dwd.dwd_trade_order_df"));

        verify(modelRepo, never()).findByTenantIdAndSourceFqnOrderByCreatedAtDesc(eq(TENANT_ID), eq("dwd.dwd_trade_order_df"));
        verify(dwdModelService, never()).run(eq(MODEL_ID), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void skipsHistoricalOdsEventOlderThanValidatedModel() {
        DataModel model = model("VALIDATED");
        model.setUpdatedAt(Instant.parse("2026-06-22T12:00:00Z"));
        when(modelRepo.findByTenantIdAndSourceFqnOrderByCreatedAtDesc(TENANT_ID, "ods.ods_codex_orders"))
            .thenReturn(List.of(model));

        handler.handle(event("ods.ods_codex_orders", Instant.parse("2026-06-22T11:59:00Z")));

        verify(dwdModelService, never()).run(eq(MODEL_ID), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void skipsModelThatIsNotValidated() {
        when(modelRepo.findByTenantIdAndSourceFqnOrderByCreatedAtDesc(TENANT_ID, "ods.ods_codex_orders"))
            .thenReturn(List.of(model("DRAFT")));

        handler.handle(event("ods.ods_codex_orders"));

        verify(dwdModelService, never()).run(eq(MODEL_ID), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void skipsEventWithMissingTargetTableOrTenantId() {
        OutboxEvent event = new OutboxEvent();
        event.setId(UUID.randomUUID());
        event.setEventType(DomainEvents.INTEGRATION_TABLE_LOADED);
        event.setPayload(JsonUtil.toJson(Map.of("targetTable", "ods.ods_codex_orders")));

        handler.handle(event);

        verify(modelRepo, never()).findByTenantIdAndSourceFqnOrderByCreatedAtDesc(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(dwdModelService, never()).run(eq(MODEL_ID), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void skipsEventWithBadTenantId() {
        OutboxEvent event = new OutboxEvent();
        event.setId(UUID.randomUUID());
        event.setEventType(DomainEvents.INTEGRATION_TABLE_LOADED);
        event.setPayload(JsonUtil.toJson(Map.of(
            "tenantId", "not-a-uuid",
            "targetTable", "ods.ods_codex_orders",
            "namespace", "ods"
        )));

        handler.handle(event);

        verify(modelRepo, never()).findByTenantIdAndSourceFqnOrderByCreatedAtDesc(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(dwdModelService, never()).run(eq(MODEL_ID), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void restoresPreviousTenantContextAfterTriggering() {
        UUID previousTenant = UUID.fromString("99999999-9999-9999-9999-999999999999");
        TenantContext.setTenantId(previousTenant);
        when(modelRepo.findByTenantIdAndSourceFqnOrderByCreatedAtDesc(TENANT_ID, "ods.ods_codex_orders"))
            .thenReturn(List.of(model("VALIDATED")));

        handler.handle(event("ods.ods_codex_orders"));

        verify(dwdModelService).run(eq(MODEL_ID), org.mockito.ArgumentMatchers.any());
        assertThat(TenantContext.getTenantId()).isEqualTo(previousTenant);
    }

    private OutboxEvent event(String targetTable) {
        return event(targetTable, Instant.now());
    }

    private OutboxEvent event(String targetTable, Instant occurredAt) {
        OutboxEvent event = new OutboxEvent();
        event.setId(UUID.randomUUID());
        event.setEventType(DomainEvents.INTEGRATION_TABLE_LOADED);
        event.setOccurredAt(occurredAt);
        event.setPayload(JsonUtil.toJson(Map.of(
            "tenantId", TENANT_ID.toString(),
            "runId", SYNC_RUN_ID.toString(),
            "targetTable", targetTable,
            "namespace", targetTable.substring(0, targetTable.indexOf('.'))
        )));
        return event;
    }

    private DataModel model(String status) {
        DataModel model = new DataModel();
        model.setId(MODEL_ID);
        model.setTenantId(TENANT_ID);
        model.setName("dwd_trade_codex_orders_df");
        model.setLayer("DWD");
        model.setSourceFqn("ods.ods_codex_orders");
        model.setTargetFqn("dwd.dwd_trade_codex_orders_df");
        model.setStatus(status);
        return model;
    }
}
