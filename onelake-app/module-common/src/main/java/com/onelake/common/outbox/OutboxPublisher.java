package com.onelake.common.outbox;

import com.onelake.common.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Outbox 发布器（对应《技术初始化文档》§6.13）。
 * 调用方在业务事务内调用 publish(...)，与业务写操作在同一事务，保证「写库即发事件」。
 */
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    private final OutboxEventRepository repo;

    public void publish(String type, String aggregateId, Object payload) {
        OutboxEvent e = new OutboxEvent();
        e.setEventType(type);
        e.setAggregateId(aggregateId);
        e.setPayload(JsonUtil.toJson(payload));
        e.setStatus(OutboxEvent.Status.PENDING);
        e.setOccurredAt(java.time.Instant.now());
        repo.save(e);
    }

    public void publish(String type, String aggregateId, Map<String, Object> payload) {
        publish(type, aggregateId, (Object) payload);
    }
}
