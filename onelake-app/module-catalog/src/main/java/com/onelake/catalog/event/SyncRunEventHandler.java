package com.onelake.catalog.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onelake.catalog.domain.entity.Asset;
import com.onelake.catalog.repository.AssetRepository;
import com.onelake.common.outbox.DomainEventHandler;
import com.onelake.common.outbox.OutboxEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 消费 integration.sync_run.* 事件，刷新 catalog 资产的 syncedAt 时间戳。
 *
 * <p>设计意图（CLAUDE.md §3 旅程一）：
 * <ul>
 *   <li>{@code sync_run.succeeded} → 资产 syncedAt = now，标记数据已新鲜</li>
 *   <li>{@code sync_run.failed}    → 仅记录日志，资产不变（由监控/告警单独处理）</li>
 * </ul>
 *
 * <p>payload 字段（来自 integration.SyncTaskServiceImpl.reconcile）：
 * <pre>
 * { "taskId": ..., "externalJobId": ..., "status": ..., "targetTable": "ods.xxx", "tenantId": ... }
 * </pre>
 *
 * <p>定位资产：{@code catalog.asset.om_fqn = payload.targetTable AND tenant_id = payload.tenantId}。
 * 找不到则忽略（资产可能尚未通过 OM 同步过来）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SyncRunEventHandler implements DomainEventHandler {

    private final AssetRepository assetRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public boolean supports(String eventType) {
        return "integration.sync_run.succeeded".equals(eventType)
            || "integration.sync_run.failed".equals(eventType);
    }

    @Override
    @Transactional
    public void handle(OutboxEvent event) {
        try {
            JsonNode p = objectMapper.readTree(event.getPayload() == null ? "{}" : event.getPayload());
            String targetTable = p.path("targetTable").asText("");
            String tenantIdRaw = p.path("tenantId").asText("");
            String status = p.path("status").asText("");

            if (targetTable.isBlank() || tenantIdRaw.isBlank()) {
                log.warn("SyncRunEventHandler skipped event {} (missing targetTable/tenantId in payload)", event.getId());
                return;
            }
            UUID tenantId;
            try {
                tenantId = UUID.fromString(tenantIdRaw);
            } catch (IllegalArgumentException e) {
                log.warn("SyncRunEventHandler skipped event {} (bad tenantId {})", event.getId(), tenantIdRaw);
                return;
            }

            Optional<Asset> found = assetRepo.findByTenantIdAndOmFqn(tenantId, targetTable);
            if (found.isEmpty()) {
                log.info("SyncRunEventHandler: asset {} not yet indexed by OM, skipping", targetTable);
                return;
            }
            Asset a = found.get();
            if ("SUCCEEDED".equalsIgnoreCase(status)) {
                a.setSyncedAt(Instant.now());
                assetRepo.save(a);
                log.info("SyncRunEventHandler: asset {} syncedAt refreshed after sync_run.succeeded", targetTable);
            } else {
                log.info("SyncRunEventHandler: sync_run.failed for {} — asset untouched, monitor will alert", targetTable);
            }
        } catch (Exception e) {
            log.error("SyncRunEventHandler failed for event {}: {}", event.getId(), e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }
}
