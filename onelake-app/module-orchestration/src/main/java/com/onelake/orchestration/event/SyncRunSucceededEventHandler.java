package com.onelake.orchestration.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 旧版 definition-JSON 事件触发兼容逻辑。
 *
 * <p>设计意图（CLAUDE.md §3 旅程一闭环）：
 * 采集任务完成 → 数据已落入 ODS → 自动触发加工 DAG（清洗/脱敏/入 DWD/DWS）。
 *
 * <p>匹配逻辑：遍历 tenant 下所有 enabled=true 的 DAG，
 * 解析 definition JSON 中的 INPUT 类型节点，若 node.name 匹配 payload.targetTable 则触发。
 * 新 V2 流水线优先使用 {@link PipelineSyncRefTriggerHandler} 的结构化 SYNC_REF 路径。
 * 本类不再独立注册 Redis Stream 订阅，由新处理器在同一次事件分发中显式调用，避免
 * 相同 consumer group 下两个 Handler 竞争同一条消息。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SyncRunSucceededEventHandler {

    private final DagRepository dagRepo;
    private final OrchestrationService orchestrationService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 解析旧 definition 依赖并逐 DAG 最佳努力触发；单条失败不阻断其他 DAG。 */
    public void handle(OutboxEvent event) {
        try {
            JsonNode p = objectMapper.readTree(event.getPayload() == null ? "{}" : event.getPayload());
            String targetTable = p.path("targetTable").asText("");
            String tenantIdRaw = p.path("tenantId").asText("");

            if (targetTable.isBlank() || tenantIdRaw.isBlank()) {
                log.warn("SyncRunSucceededEventHandler：缺少 targetTable/tenantId");
                return;
            }

            UUID tenantId = UUID.fromString(tenantIdRaw);
            List<Dag> enabledDags = dagRepo.findByTenantId(tenantId).stream()
                .filter(Dag::getEnabled)
                // 已绑定发布快照的 V2 流水线由统一资产处理器负责，避免新旧路径双触发。
                .filter(dag -> dag.getPublishedVersionId() == null)
                .toList();

            int triggered = 0;
            for (Dag dag : enabledDags) {
                if (dagDependsOn(dag, targetTable)) {
                    try {
                        orchestrationService.triggerDag(dag.getId(), TriggerType.EVENT);
                        triggered++;
                        log.info("SyncRunSucceededEventHandler：表 {} 同步后已触发 DAG {} ({})",
                            targetTable, dag.getName(), dag.getId());
                    } catch (Exception e) {
                        log.warn("SyncRunSucceededEventHandler：触发 DAG {} 失败：{}",
                            dag.getName(), e.getMessage());
                    }
                }
            }
            if (triggered == 0) {
                log.debug("SyncRunSucceededEventHandler：没有 DAG 依赖 {}，跳过", targetTable);
            }
        } catch (Exception e) {
            log.error("SyncRunSucceededEventHandler 处理事件 {} 失败：{}", event.getId(), e.getMessage(), e);
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
            log.warn("dagDependsOn：解析 DAG {} 的 definition 失败：{}", dag.getName(), e.getMessage());
        }
        return false;
    }
}
