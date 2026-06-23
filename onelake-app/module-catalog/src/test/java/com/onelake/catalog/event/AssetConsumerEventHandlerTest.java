package com.onelake.catalog.event;

import com.onelake.catalog.domain.entity.AssetConsumer;
import com.onelake.catalog.repository.AssetConsumerRepository;
import com.onelake.common.outbox.DomainEvents;
import com.onelake.common.outbox.OutboxEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssetConsumerEventHandlerTest {

    private static final UUID TENANT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private AssetConsumerRepository repo;
    private AssetConsumerEventHandler handler;

    @BeforeEach
    void setUp() {
        repo = mock(AssetConsumerRepository.class);
        when(repo.save(any(AssetConsumer.class))).thenAnswer(inv -> inv.getArgument(0));
        handler = new AssetConsumerEventHandler(repo);
    }

    @Test
    void upsertsApiConsumerOnPublished() {
        when(repo.findByTenantIdAndAssetFqnAndConsumerTypeAndConsumerRef(
            TENANT_ID, "ods.orders", "API", "api-1"))
            .thenReturn(Optional.empty());

        handler.handle(event(DomainEvents.DATASERVICE_API_PUBLISHED, """
            {"tenantId":"%s","assetFqn":"ods.orders","consumerType":"API",
             "consumerRef":"api-1","consumerName":"/api/order","ownerName":"alice","action":"UPSERT"}
            """.formatted(TENANT_ID)));

        org.mockito.ArgumentCaptor<AssetConsumer> captor =
            org.mockito.ArgumentCaptor.forClass(AssetConsumer.class);
        verify(repo).save(captor.capture());
        AssetConsumer saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo("ACTIVE");
        assertThat(saved.getAssetFqn()).isEqualTo("ods.orders");
        assertThat(saved.getConsumerName()).isEqualTo("/api/order");
        assertThat(saved.getOwnerName()).isEqualTo("alice");
    }

    @Test
    void marksRemovedOnOffline() {
        AssetConsumer existing = new AssetConsumer();
        existing.setTenantId(TENANT_ID);
        existing.setAssetFqn("ods.orders");
        existing.setConsumerType("API");
        existing.setConsumerRef("api-1");
        existing.setStatus("ACTIVE");
        when(repo.findByTenantIdAndAssetFqnAndConsumerTypeAndConsumerRef(
            TENANT_ID, "ods.orders", "API", "api-1"))
            .thenReturn(Optional.of(existing));

        handler.handle(event(DomainEvents.DATASERVICE_API_OFFLINE, """
            {"tenantId":"%s","assetFqn":"ods.orders","consumerType":"API",
             "consumerRef":"api-1","action":"REMOVE"}
            """.formatted(TENANT_ID)));

        assertThat(existing.getStatus()).isEqualTo("REMOVED");
        verify(repo).save(existing);
    }

    @Test
    void skipsEventWithMissingFields() {
        handler.handle(event(DomainEvents.DATASERVICE_API_PUBLISHED, """
            {"tenantId":"%s","assetFqn":""}
            """.formatted(TENANT_ID)));
        org.mockito.Mockito.verifyNoInteractions(repo);
    }

    private OutboxEvent event(String type, String payload) {
        OutboxEvent e = new OutboxEvent();
        e.setId(UUID.randomUUID());
        e.setEventType(type);
        e.setTenantId(TENANT_ID);
        e.setAggregateType("dataservice");
        e.setAggregateId("agg-1");
        e.setPayload(payload);
        return e;
    }
}
