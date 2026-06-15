package com.onelake.catalog.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onelake.common.outbox.DomainEvents;
import com.onelake.common.outbox.DomainEventHandler;
import com.onelake.common.outbox.OutboxEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 消费 integration.datasource.deleted 事件。
 *
 * <p>当前实现是日志占位 —— 真实数据源删除时，OM 那边通常会通过自己的软删除流程处理资产，
 * 我们这边只在日志里记一笔，方便排查"为什么某资产突然消失"。
 *
 * <p>未来若需要更强一致：可在此调用 OpenMetadataClient.softDelete(fqn) 或本地
 * catalog.asset 标记 source_deleted=true（具体看 OM API 演进）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DatasourceDeletedEventHandler implements DomainEventHandler {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Set<String> eventTypes() {
        return Set.of(DomainEvents.INTEGRATION_DATASOURCE_DELETED);
    }

    @Override
    public void handle(OutboxEvent event) {
        try {
            JsonNode p = objectMapper.readTree(event.getPayload() == null ? "{}" : event.getPayload());
            String name = p.path("name").asText("");
            String type = p.path("type").asText("");
            log.info("DatasourceDeletedEventHandler: datasource {} ({}) deleted — OM asset cleanup will follow via OM's own soft-delete", name, type);
        } catch (Exception e) {
            log.error("DatasourceDeletedEventHandler failed for event {}: {}", event.getId(), e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }
}
