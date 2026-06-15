package com.onelake.common.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.onelake.common.util.JsonUtil;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Outbox 分发器（对应《技术初始化文档》§6.7 + §6.13）。
 * 每 1 秒扫描一次 PENDING 事件，投递到 Redis Stream；失败重试，超过阈值置 DEAD。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxDispatcher {

    private static final int MAX_RETRY = 8;

    private final OutboxEventRepository repo;
    private final StringRedisTemplate redis;

    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void dispatch() {
        List<OutboxEvent> pending = repo.lockPendingBatch();
        if (pending.isEmpty()) return;
        log.debug("dispatching {} outbox events", pending.size());

        for (OutboxEvent e : pending) {
            try {
                String body = JsonUtil.toJson(EventEnvelope.of(e));
                redis.opsForStream().add(StreamRecords.string(Map.of("data", body))
                    .withStreamKey(streamKey(e.getEventType())));
                e.setStatus(OutboxEvent.Status.PUBLISHED);
                e.setPublishedAt(Instant.now());
            } catch (Exception ex) {
                int retryCount = e.getRetryCount() + 1;
                e.setRetryCount(retryCount);
                if (retryCount >= MAX_RETRY) {
                    e.setStatus(OutboxEvent.Status.DEAD);
                }
                log.warn("outbox event {} dispatch failed (retry={}): {}", e.getId(), retryCount, ex.getMessage());
            }
        }
        repo.saveAll(pending);
    }

    public static String streamKey(String eventType) {
        return "stream:" + eventType;
    }
}
