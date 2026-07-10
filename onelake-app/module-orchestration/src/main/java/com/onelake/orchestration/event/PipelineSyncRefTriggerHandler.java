package com.onelake.orchestration.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.outbox.DomainEvents;
import com.onelake.common.outbox.DomainEventHandler;
import com.onelake.common.outbox.OutboxEvent;
import com.onelake.orchestration.domain.entity.Dag;
import com.onelake.orchestration.domain.entity.PipelineTask;
import com.onelake.orchestration.domain.entity.PipelineTaskEdge;
import com.onelake.orchestration.domain.enums.EdgeLayer;
import com.onelake.orchestration.domain.enums.TaskType;
import com.onelake.orchestration.domain.enums.TriggerType;
import com.onelake.orchestration.repository.DagRepository;
import com.onelake.orchestration.repository.PipelineTaskEdgeRepository;
import com.onelake.orchestration.repository.PipelineTaskRepository;
import com.onelake.orchestration.service.OrchestrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 流水线 V2 事件触发器：消费 {@code integration.table.loaded} 后触发下游流水线。
 *
 * <p>这是编排模块侧的 ODS 表就绪触发路径。建模模块不再直接启动 DWD 作业；
 * ODS 事件驱动统一从这里进入编排。
 *
 * <p>匹配规则：流水线包含 {@link TaskType#SYNC_REF} 节点，且节点 {@code target_fqn}
 * 等于事件载荷里的 {@code targetTable}。匹配后调用
 * {@link OrchestrationService#triggerPipelineRun(UUID, TriggerType)}。
 *
 * <p>Outbox handler 在后台线程执行，因此需要从事件载荷恢复 {@link TenantContext}。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PipelineSyncRefTriggerHandler implements DomainEventHandler {

    private final DagRepository dagRepo;
    private final PipelineTaskRepository taskRepo;
    private final PipelineTaskEdgeRepository edgeRepo;
    private final OrchestrationService orchestrationService;
    /** Handler 无请求级 ObjectMapper 注入需求，使用局部 JSON 解析器读取稳定事件载荷。 */
    private final ObjectMapper objectMapper = new ObjectMapper();
    /**
     * 多 SYNC_REF 输入的进程内就绪屏障：dagId -> taskKey -> 最近就绪时间。
     * 持久化跨批次屏障属于后续演进，本结构用于避免单实例内过早触发。
     */
    private final Map<UUID, Map<String, Instant>> readinessByDag = new ConcurrentHashMap<>();

    /** 声明本 Handler 只消费集成表落地成功事件。 */
    @Override
    public Set<String> eventTypes() {
        return Set.of(DomainEvents.INTEGRATION_TABLE_LOADED);
    }

    /**
     * 解析事件租户和目标表，匹配 SYNC_REF 节点并在恢复的租户上下文中尝试触发流水线。
     */
    @Override
    public void handle(OutboxEvent event) {
        try {
            JsonNode payload = objectMapper.readTree(event.getPayload() == null ? "{}" : event.getPayload());
            String targetTable = payload.path("targetTable").asText("");
            String tenantIdRaw = payload.path("tenantId").asText("");
            if (targetTable.isBlank() || tenantIdRaw.isBlank()) {
                log.debug("PipelineSyncRefTriggerHandler 跳过事件 {}：缺少 targetTable/tenantId",
                        event.getId());
                return;
            }

            UUID tenantId;
            try {
                tenantId = UUID.fromString(tenantIdRaw);
            } catch (IllegalArgumentException e) {
                log.warn("PipelineSyncRefTriggerHandler 跳过事件 {}：tenantId 非法 {}",
                        event.getId(), tenantIdRaw);
                return;
            }

            // 先按租户和节点类型缩小集合，再用 targetFqn 精确匹配事件目标表。
            List<PipelineTask> syncRefs = taskRepo.findByTenantIdAndTaskType(
                    tenantId, TaskType.SYNC_REF.name());
            Map<UUID, Set<String>> pipelineReadyTasks = new HashMap<>();
            for (PipelineTask t : syncRefs) {
                if (targetTable.equals(t.getTargetFqn())) {
                    pipelineReadyTasks.computeIfAbsent(t.getDagId(), ignored -> new HashSet<>())
                            .add(t.getTaskKey());
                }
            }
            if (pipelineReadyTasks.isEmpty()) {
                log.debug("PipelineSyncRefTriggerHandler：没有流水线引用目标表 {}", targetTable);
                return;
            }

            // Outbox 在线程池中执行；触发服务依赖 TenantContext，因此必须显式恢复并还原。
            UUID previousTenant = TenantContext.getTenantId();
            try {
                TenantContext.setTenantId(tenantId);
                for (Map.Entry<UUID, Set<String>> entry : pipelineReadyTasks.entrySet()) {
                    triggerPipeline(entry.getKey(), tenantId, entry.getValue(), event.getOccurredAt());
                }
            } finally {
                if (previousTenant == null) {
                    TenantContext.clear();
                } else {
                    TenantContext.setTenantId(previousTenant);
                }
            }
        } catch (Exception e) {
            log.error("PipelineSyncRefTriggerHandler 处理事件 {} 失败：{}",
                    event.getId(), e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    private void triggerPipeline(UUID dagId, UUID tenantId, Set<String> readyTaskKeys, Instant eventOccurredAt) {
        try {
            Dag dag = dagRepo.findByIdAndTenantId(dagId, tenantId).orElse(null);
            if (dag == null) {
                log.warn("PipelineSyncRefTriggerHandler：租户 {} 下未找到 dag {}", tenantId, dagId);
                return;
            }
            if (!Boolean.TRUE.equals(dag.getEnabled())) {
                log.debug("PipelineSyncRefTriggerHandler：dag {} 已禁用，跳过", dagId);
                return;
            }
            if (dag.getStatus() != null
                    && !"VALIDATED".equalsIgnoreCase(dag.getStatus())
                    && !"PUBLISHED".equalsIgnoreCase(dag.getStatus())) {
                log.debug("PipelineSyncRefTriggerHandler：dag {} 状态为 {}，非 VALIDATED/PUBLISHED，跳过",
                        dagId, dag.getStatus());
                return;
            }
            if (!markReadyAndCheckBarrier(dagId, readyTaskKeys, eventOccurredAt)) {
                return;
            }
            orchestrationService.triggerPipelineRun(dagId, TriggerType.EVENT);
            clearSatisfiedReadiness(dagId);
            log.info("PipelineSyncRefTriggerHandler：表就绪后已触发流水线 {}", dagId);
        } catch (BizException e) {
            log.info("PipelineSyncRefTriggerHandler：流水线 {} 未触发，业务原因：{}",
                    dagId, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("PipelineSyncRefTriggerHandler：流水线 {} 触发失败：{}",
                    dagId, e.getMessage());
        }
    }

    /**
     * 记录本次已就绪 SYNC_REF，并检查其共同下游的所有同步输入是否均已到达。
     *
     * <p>单输入目标无需等待；多输入目标只有缺失集合为空才允许触发整条流水线。
     */
    private boolean markReadyAndCheckBarrier(UUID dagId, Set<String> readyTaskKeys, Instant eventOccurredAt) {
        if (readyTaskKeys == null || readyTaskKeys.isEmpty()) {
            return false;
        }
        Map<String, Instant> ready = readinessByDag.computeIfAbsent(dagId, ignored -> new ConcurrentHashMap<>());
        Instant readyAt = eventOccurredAt == null ? Instant.now() : eventOccurredAt;
        readyTaskKeys.forEach(taskKey -> ready.put(taskKey, readyAt));

        List<PipelineTask> tasks = taskRepo.findByDagIdOrderByCreatedAtAsc(dagId);
        if (tasks == null) {
            tasks = List.of();
        }
        Map<String, PipelineTask> taskByKey = tasks.stream()
                .collect(Collectors.toMap(PipelineTask::getTaskKey, task -> task, (a, b) -> a));
        List<PipelineTaskEdge> allEdges = edgeRepo.findByDagId(dagId);
        if (allEdges == null) {
            allEdges = List.of();
        }
        List<PipelineTaskEdge> edges = allEdges.stream()
                .filter(edge -> edge.getEdgeLayer() == EdgeLayer.PIPELINE)
                .toList();

        Set<String> touchedTargets = edges.stream()
                .filter(edge -> readyTaskKeys.contains(edge.getSourceKey()))
                .map(PipelineTaskEdge::getTargetKey)
                .collect(Collectors.toSet());
        if (touchedTargets.isEmpty()) {
            return true;
        }
        for (String targetKey : touchedTargets) {
            List<PipelineTaskEdge> syncInputs = edges.stream()
                    .filter(edge -> targetKey.equals(edge.getTargetKey()))
                    .filter(edge -> {
                        PipelineTask source = taskByKey.get(edge.getSourceKey());
                        return source != null && source.getTaskType() == TaskType.SYNC_REF;
                    })
                    .toList();
            if (syncInputs.size() <= 1) {
                continue;
            }
            List<String> missing = syncInputs.stream()
                    .map(PipelineTaskEdge::getSourceKey)
                    .filter(sourceKey -> !ready.containsKey(sourceKey))
                    .toList();
            if (!missing.isEmpty()) {
                log.info("PipelineSyncRefTriggerHandler：dag {} 等待目标节点 {} 的输入就绪，缺少 {}",
                        dagId, targetKey, missing);
                return false;
            }
        }
        return true;
    }

    /** 流水线成功触发后清除本轮屏障，允许下一批输入重新汇合。 */
    private void clearSatisfiedReadiness(UUID dagId) {
        readinessByDag.remove(dagId);
    }
}
