package com.onelake.common.outbox;

import com.onelake.common.context.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;

/**
 * Redis Stream consumer-group bridge for in-process domain handlers.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisStreamDomainEventConsumer {

    private final StringRedisTemplate redis;
    private final ConsumedEventRepository consumedRepo;
    private final List<DomainEventHandler> handlers;

    @Scheduled(fixedDelay = 1000)
    public void consume() {
        for (DomainEventHandler handler : handlers) {
            for (String eventType : handler.eventTypes()) {
                String streamKey = OutboxDispatcher.streamKey(eventType);
                ensureGroup(streamKey, handler.consumerGroup());
                consume(handler, streamKey, ReadOffset.from("0"));
                consume(handler, streamKey, ReadOffset.lastConsumed());
            }
        }
    }

    private void ensureGroup(String streamKey, String group) {
        try {
            redis.opsForStream().createGroup(streamKey, ReadOffset.from("0"), group);
        } catch (DataAccessException e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            if (!message.contains("BUSYGROUP") && !message.contains("no such key")) {
                log.debug("create redis stream group failed for {} / {}: {}", streamKey, group, message);
            }
        }
    }

    private void consume(DomainEventHandler handler, String streamKey, ReadOffset readOffset) {
        List<MapRecord<String, Object, Object>> records;
        try {
            records = redis.opsForStream().read(
                Consumer.from(handler.consumerGroup(), handler.consumerName()),
                org.springframework.data.redis.connection.stream.StreamReadOptions.empty()
                    .count(20)
                    .block(Duration.ofMillis(100)),
                StreamOffset.create(streamKey, readOffset)
            );
        } catch (DataAccessException e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            if (!message.contains("NOGROUP") && !message.contains("no such key")) {
                log.debug("redis stream read failed for {} / {}: {}", streamKey, handler.consumerGroup(), message);
            }
            return;
        }
        if (records == null || records.isEmpty()) {
            return;
        }
        for (MapRecord<String, Object, Object> record : records) {
            handleRecord(handler, streamKey, record);
        }
    }

    @Transactional
    protected void handleRecord(DomainEventHandler handler, String streamKey, MapRecord<String, Object, Object> record) {
        Object rawBody = record.getValue().get("data");
        String body = rawBody == null ? null : rawBody.toString();
        if (body == null || body.isBlank()) {
            redis.opsForStream().acknowledge(streamKey, handler.consumerGroup(), record.getId());
            return;
        }
        EventEnvelope envelope = EventEnvelope.parse(body);
        ConsumedEventId id = new ConsumedEventId(envelope.getEventId(), handler.consumerGroup());
        if (consumedRepo.existsById(id)) {
            redis.opsForStream().acknowledge(streamKey, handler.consumerGroup(), record.getId());
            return;
        }

        try {
            if (envelope.getTenantId() != null) {
                TenantContext.setTenantId(envelope.getTenantId());
            }
            TenantContext.setTraceId("event:" + envelope.getEventId());
            handler.handle(envelope.toEvent());
            ConsumedEvent consumed = new ConsumedEvent();
            consumed.setEventId(envelope.getEventId());
            consumed.setConsumer(handler.consumerGroup());
            consumedRepo.save(consumed);
            redis.opsForStream().acknowledge(streamKey, handler.consumerGroup(), record.getId());
        } catch (Exception e) {
            log.warn("domain event consumer {} failed for event {}: {}",
                handler.consumerName(), envelope.getEventId(), e.getMessage());
        } finally {
            TenantContext.clear();
        }
    }
}
