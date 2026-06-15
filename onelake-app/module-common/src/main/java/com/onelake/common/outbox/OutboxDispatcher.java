package com.onelake.common.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Outbox 分发器（对应《技术初始化文档》§6.7 + §6.13）。
 * 每 2 秒扫描一次 PENDING 事件，按注册的 handler 分发；失败置 FAILED。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxDispatcher {

    private final OutboxEventRepository repo;
    private final List<DomainEventHandler> handlers;

    @Scheduled(fixedDelay = 2000)
    @Transactional
    public void dispatch() {
        List<OutboxEvent> pending = repo.findTop100Pending();
        if (pending.isEmpty()) return;
        log.debug("dispatching {} outbox events", pending.size());

        for (OutboxEvent e : pending) {
            try {
                boolean handled = false;
                for (DomainEventHandler h : handlers) {
                    if (h.supports(e.getEventType())) {
                        h.handle(e);
                        handled = true;
                    }
                }
                e.setStatus(handled ? OutboxEvent.Status.SENT : OutboxEvent.Status.FAILED);
            } catch (Exception ex) {
                log.warn("outbox event {} dispatch failed: {}", e.getId(), ex.getMessage());
                e.setStatus(OutboxEvent.Status.FAILED);
            }
        }
        repo.saveAll(pending);
    }
}
