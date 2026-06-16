package com.onelake.common.outbox;

import java.util.Set;

/**
 * 领域事件处理器 SPI。新模块实现该接口并在 Spring 容器注册后，由 Redis Stream 消费调度器调用。
 */
public interface DomainEventHandler {

    /**
     * 该处理器订阅的事件类型。
     */
    Set<String> eventTypes();

    /**
     * 消费者组。默认按 com.onelake.<module> 的模块名分组。
     */
    default String consumerGroup() {
        String packageName = getClass().getPackageName();
        String prefix = "com.onelake.";
        if (packageName.startsWith(prefix)) {
            String remaining = packageName.substring(prefix.length());
            int dot = remaining.indexOf('.');
            return dot > 0 ? remaining.substring(0, dot) : remaining;
        }
        return "default";
    }

    /**
     * 消费者名称。默认使用类名，便于在 Redis Stream pending 列表中排查。
     */
    default String consumerName() {
        return getClass().getSimpleName();
    }

    /**
     * 该处理器是否接受事件类型。
     */
    default boolean supports(String eventType) {
        return eventTypes().contains(eventType);
    }

    /**
     * 处理事件。抛异常即视为本次消费失败，消息保留在 Redis Stream pending 列表等待重试。
     */
    void handle(OutboxEvent event);
}
