package com.onelake.common.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.onelake.common.util.JsonUtil;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Stable event envelope delivered through Redis Stream.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventEnvelope {

    public static final int CURRENT_VERSION = 1;

    private UUID eventId;
    private String eventType;
    private UUID tenantId;
    private String aggregateId;
    private Instant occurredAt;
    private int version;
    private JsonNode payload;

    public static EventEnvelope of(OutboxEvent event) {
        return EventEnvelope.builder()
            .eventId(event.getId())
            .eventType(event.getEventType())
            .tenantId(event.getTenantId())
            .aggregateId(event.getAggregateId())
            .occurredAt(event.getOccurredAt())
            .version(CURRENT_VERSION)
            .payload(JsonUtil.parse(event.getPayload() == null ? "{}" : event.getPayload()))
            .build();
    }

    public static EventEnvelope parse(String json) {
        return JsonUtil.fromJson(json, EventEnvelope.class);
    }

    public OutboxEvent toEvent() {
        OutboxEvent event = new OutboxEvent();
        event.setId(eventId);
        event.setEventType(eventType);
        event.setTenantId(tenantId);
        event.setAggregateId(aggregateId);
        event.setAggregateType(OutboxPublisher.aggregateTypeOf(eventType));
        event.setOccurredAt(occurredAt);
        event.setPayload(payload == null ? "{}" : JsonUtil.toJson(payload));
        event.setStatus(OutboxEvent.Status.PUBLISHED);
        return event;
    }
}
