package com.onelake.common.outbox;

/**
 * 领域事件处理器 SPI。新模块实现该接口并在 Spring 容器注册即被分发器调用。
 */
public interface DomainEventHandler {

    /**
     * 该处理器接受的事件类型前缀，多个用 | 分隔或单独返回 true。
     */
    boolean supports(String eventType);

    /**
     * 处理事件。抛异常即视为失败，会被分发器标记为 FAILED。
     */
    void handle(OutboxEvent event);
}
