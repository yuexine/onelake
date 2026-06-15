package com.onelake.common.alert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onelake.common.outbox.DomainEvents;
import com.onelake.common.outbox.DomainEventHandler;
import com.onelake.common.outbox.OutboxEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

/**
 * 消费 integration.sync_run.failed 事件，自动创建运营告警。
 *
 * <p>每次采集任务失败都生成一条 P1 告警（连续 3 次由后续聚合规则升级 P0）。
 * 这条告警会被前端 AlertCenter 页面展示，运营可认领/关闭。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SyncRunFailedAlertHandler implements DomainEventHandler {

    private final AlertRepository alertRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Set<String> eventTypes() {
        return Set.of(DomainEvents.INTEGRATION_SYNC_FAILED);
    }

    @Override
    @Transactional
    public void handle(OutboxEvent event) {
        try {
            JsonNode p = objectMapper.readTree(event.getPayload() == null ? "{}" : event.getPayload());
            String targetTable = p.path("targetTable").asText("");
            String tenantIdRaw = p.path("tenantId").asText("");
            String runId = event.getAggregateId();

            if (tenantIdRaw.isBlank()) {
                log.warn("SyncRunFailedAlertHandler: missing tenantId in payload");
                return;
            }

            Alert alert = new Alert();
            alert.setTenantId(UUID.fromString(tenantIdRaw));
            alert.setLevel("P1");
            alert.setSource("采集");
            alert.setTitle("采集任务失败: " + (targetTable.isBlank() ? runId : targetTable));
            alert.setRule("sync_run 失败自动告警");
            alert.setStatus("OPEN");
            if (!runId.isBlank()) {
                try { alert.setRelatedRunId(UUID.fromString(runId)); } catch (Exception ignored) {}
            }
            alertRepo.save(alert);
            log.info("SyncRunFailedAlertHandler: created P1 alert for sync_run {} (table={})", runId, targetTable);
        } catch (Exception e) {
            log.error("SyncRunFailedAlertHandler failed for event {}: {}", event.getId(), e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }
}
