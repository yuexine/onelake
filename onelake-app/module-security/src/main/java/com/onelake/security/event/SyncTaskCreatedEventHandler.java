package com.onelake.security.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onelake.common.outbox.DomainEventHandler;
import com.onelake.common.outbox.OutboxEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 消费 integration.sync_task.created 事件，把新建的采集目标表加入 PII 扫描队列。
 *
 * <p>当前实现：日志记录（stub）。
 *
 * <p>真实集成路径（待 PiiScanService 接入）：
 * <ol>
 *   <li>解析 payload.targetTable + tenantId</li>
 *   <li>调用 PiiScanService.enqueueScan(tenantId, targetTable)</li>
 *   <li>扫描结果再发 security.pii_scan.completed 事件，由目录/脱敏模块消费</li>
 * </ol>
 *
 * <p>这是"采集即扫描"的核心链路 —— CLAUDE.md §3 旅程三的自动化基础。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SyncTaskCreatedEventHandler implements DomainEventHandler {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public boolean supports(String eventType) {
        return "integration.sync_task.created".equals(eventType);
    }

    @Override
    public void handle(OutboxEvent event) {
        try {
            JsonNode p = objectMapper.readTree(event.getPayload() == null ? "{}" : event.getPayload());
            String name = p.path("name").asText("");
            String targetTable = p.path("targetTable").asText("");
            String tenantId = p.path("tenantId").asText("");
            String mode = p.path("mode").asText("");

            log.info("SyncTaskCreatedEventHandler: enqueuing PII scan for syncTask={} targetTable={} tenant={} mode={} (stub — PiiScanService not wired)",
                name, targetTable, tenantId, mode);

            // TODO: 当 PiiScanService 接入后，调用 enqueueScan(tenantId, targetTable)
            // piiScanService.enqueueScan(UUID.fromString(tenantId), targetTable);
        } catch (Exception e) {
            log.error("SyncTaskCreatedEventHandler failed for event {}: {}", event.getId(), e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }
}
