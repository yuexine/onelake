package com.onelake.security.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.onelake.common.outbox.DomainEvents;
import com.onelake.common.outbox.DomainEventHandler;
import com.onelake.common.outbox.OutboxEvent;
import com.onelake.security.service.PiiScanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 消费 integration.sync_task.created 事件，自动触发 PII 扫描。
 *
 * <p>这是 CLAUDE.md §3 旅程三"采集即扫描"的核心链路 —— 新建采集任务后，
 * 安全模块自动对目标表做 PII 检测，无需人工触发。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SyncTaskCreatedEventHandler implements DomainEventHandler {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PiiScanService piiScanService;

    @Override
    public Set<String> eventTypes() {
        return Set.of(DomainEvents.INTEGRATION_SYNC_TASK_CREATED);
    }

    @Override
    public void handle(OutboxEvent event) {
        try {
            JsonNode p = objectMapper.readTree(event.getPayload() == null ? "{}" : event.getPayload());
            String targetTable = p.path("targetTable").asText("");
            String tenantIdRaw = p.path("tenantId").asText("");
            String name = p.path("name").asText("");

            if (targetTable.isBlank() || tenantIdRaw.isBlank()) {
                log.warn("SyncTaskCreatedEventHandler: missing targetTable/tenantId, skipping");
                return;
            }

            UUID tenantId = UUID.fromString(tenantIdRaw);
            int detected = piiScanService.enqueueScan(tenantId, targetTable, fieldMappingOf(p.path("fieldMapping")));
            log.info("SyncTaskCreatedEventHandler: PII scan completed for syncTask={} target={} — {} sensitive fields detected",
                name, targetTable, detected);
        } catch (Exception e) {
            log.error("SyncTaskCreatedEventHandler failed for event {}: {}", event.getId(), e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    private List<Map<String, Object>> fieldMappingOf(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        return objectMapper.convertValue(node, new TypeReference<>() {});
    }
}
