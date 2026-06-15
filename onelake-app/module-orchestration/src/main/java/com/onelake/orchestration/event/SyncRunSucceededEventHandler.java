package com.onelake.orchestration.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onelake.common.outbox.DomainEventHandler;
import com.onelake.common.outbox.OutboxEvent;
import com.onelake.orchestration.domain.entity.Dag;
import com.onelake.orchestration.domain.enums.TriggerType;
import com.onelake.orchestration.repository.DagRepository;
import com.onelake.orchestration.service.OrchestrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * 消费 integration.sync_run.succeeded 事件，自动触发依赖该表的下游 DAG。
 *
 * <p>设计意图（CLAUDE.md §3 旅程一闭环）：
 * 采集任务完成 → 数据已落入 ODS → 自动触发加工 DAG（清洗/脱敏/入 DWD/DWS）。
 *
 * <p>匹配逻辑：遍历 tenant 下所有 enabled=true 的 DAG，
 * 解析 definition JSON 中的 INPUT 类型节点，若 node.name 匹配 payload.targetTable 则触发。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SyncRunSucceededEventHandler implements DomainEventHandler {

    private final DagRepository dagRepo;
    private final OrchestrationService orchestrationService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public boolean supports(String eventType) {
        return "integration.sync_run.succeeded".equals(eventType);
    }

    @Override
    public void handle(OutboxEvent event) {
        try {
            JsonNode p = objectMapper.readTree(event.getPayload() == null ? "{}" : event.getPayload());
            String targetTable = p.path("targetTable").asText("");
            String tenantIdRaw = p.path("tenantId").asText("");

            if (targetTable.isBlank() || tenantIdRaw.isBlank()) {
                log.warn("SyncRunSucceededEventHandler: missing targetTable/tenantId");
                return;
            }

            UUID tenantId = UUID.fromString(tenantIdRaw);
            List<Dag> enabledDags = dagRepo.findByTenantId(tenantId).stream()
                .filter(Dag::getEnabled)
                .toList();

            int triggered = 0;
            for (Dag dag : enabledDags) {
                if (dagDependsOn(dag, targetTable)) {
                    try {
                        orchestrationService.triggerDag(dag.getId(), TriggerType.EVENT);
                        triggered++;
                        log.info("SyncRunSucceededEventHandler: triggered DAG {} ({}) after {} sync",
                            dag.getName(), dag.getId(), targetTable);
                    } catch (Exception e) {
                        log.warn("SyncRunSucceededEventHandler: failed to trigger DAG {}: {}",
                            dag.getName(), e.getMessage());
                    }
                }
            }
            if (triggered == 0) {
                log.debug("SyncRunSucceededEventHandler: no DAGs depend on {} — skipping", targetTable);
            }
        } catch (Exception e) {
            log.error("SyncRunSucceededEventHandler failed for event {}: {}", event.getId(), e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    /**
     * 解析 DAG definition JSON，检查是否包含引用 targetTable 的 INPUT 节点。
     * definition 结构：{ "nodes": [{ "type": "input-table", "name": "ods.orders" }, ...], "edges": [...] }
     */
    private boolean dagDependsOn(Dag dag, String targetTable) {
        if (dag.getDefinition() == null || dag.getDefinition().isBlank()) return false;
        try {
            JsonNode def = objectMapper.readTree(dag.getDefinition());
            JsonNode nodes = def.path("nodes");
            if (!nodes.isArray()) return false;
            for (JsonNode node : nodes) {
                String type = node.path("type").asText("");
                String name = node.path("name").asText("");
                if (("input-table".equals(type) || "INPUT".equals(type))
                    && targetTable.equalsIgnoreCase(name)) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.warn("dagDependsOn: failed to parse definition for DAG {}: {}", dag.getName(), e.getMessage());
        }
        return false;
    }
}
