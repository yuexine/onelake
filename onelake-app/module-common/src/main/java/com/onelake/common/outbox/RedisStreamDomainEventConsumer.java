package com.onelake.common.outbox;

import com.onelake.common.context.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis Stream consumer-group bridge for in-process domain handlers.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisStreamDomainEventConsumer {

    private static final RedisScript<Long> CREATE_GROUP_SCRIPT = new DefaultRedisScript<>("""
        local result = redis.pcall('XGROUP', 'CREATE', KEYS[1], ARGV[1], '0', 'MKSTREAM')
        if type(result) == 'table' and result.err then
            if string.find(result.err, 'BUSYGROUP') then
                return 0
            end
            return redis.error_reply(result.err)
        end
        return 1
        """, Long.class);

    private final StringRedisTemplate redis;
    private final ConsumedEventRepository consumedRepo;
    private final List<DomainEventHandler> handlers;
    private final Set<String> initializedGroups = ConcurrentHashMap.newKeySet();

    @Scheduled(fixedDelay = 1000)
    public void consume() {
        for (DomainEventHandler handler : handlers) {
            for (String eventType : handler.eventTypes()) {
                String streamKey = OutboxDispatcher.streamKey(eventType);
                if (!ensureGroup(streamKey, handler.consumerGroup())) {
                    continue;
                }
                consume(handler, streamKey, ReadOffset.from("0"));
                consume(handler, streamKey, ReadOffset.lastConsumed());
            }
        }
    }

    /**
     * 使用 MKSTREAM 一次性初始化消费组并缓存结果。
     *
     * <p>旧实现每秒、每个 handler 都执行一次 XGROUP CREATE；Redisson 在不存在的
     * stream 上可能长时间等待，导致排在后面的事件延迟数十秒。消费组只需在进程内
     * 初始化一次，NOGROUP 时再清缓存重建即可。
     */
    private boolean ensureGroup(String streamKey, String group) {
        String groupKey = groupKey(streamKey, group);
        if (initializedGroups.contains(groupKey)) {
            return true;
        }
        try {
            redis.execute(CREATE_GROUP_SCRIPT, List.of(streamKey), group);
            initializedGroups.add(groupKey);
            return true;
        } catch (DataAccessException e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            if (message.contains("BUSYGROUP")) {
                initializedGroups.add(groupKey);
                return true;
            }
            if (!message.contains("no such key")) {
                log.debug("create redis stream group failed for {} / {}: {}", streamKey, group, message);
            }
            return false;
        }
    }

    private void consume(DomainEventHandler handler, String streamKey, ReadOffset readOffset) {
        List<MapRecord<String, Object, Object>> records;
        try {
            records = redis.opsForStream().read(
                Consumer.from(handler.consumerGroup(), handler.consumerName()),
                org.springframework.data.redis.connection.stream.StreamReadOptions.empty()
                    .count(20),
                StreamOffset.create(streamKey, readOffset)
            );
        } catch (DataAccessException e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            if (message.contains("NOGROUP")) {
                initializedGroups.remove(groupKey(streamKey, handler.consumerGroup()));
            }
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

    private String groupKey(String streamKey, String group) {
        return streamKey + '\u0000' + group;
    }
}
