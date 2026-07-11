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
import com.onelake.orchestration.service.OrchestrationService;
import com.onelake.orchestration.service.PipelineSnapshotService;
import com.onelake.orchestration.service.RunContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
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
 * {@link OrchestrationService#triggerPipelineRun(UUID, TriggerType)} 的 PROD 路径。
 *
 * <p>Outbox handler 在后台线程执行，因此需要从事件载荷恢复 {@link TenantContext}。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PipelineSyncRefTriggerHandler implements DomainEventHandler {

    private final DagRepository dagRepo;
    private final PipelineSnapshotService pipelineSnapshotService;
    private final OrchestrationService orchestrationService;
    /** Handler 无请求级 ObjectMapper 注入需求，使用局部 JSON 解析器读取稳定事件载荷。 */
    private final ObjectMapper objectMapper = new ObjectMapper();
    /**
     * 多 SYNC_REF 输入的进程内就绪屏障：(dagId, versionId) -> taskKey -> 最近就绪时间。
     * 持久化跨批次屏障属于后续演进，本结构用于避免单实例内过早触发。
     */
    private final Map<ReadinessKey, Map<String, Instant>> readinessByVersion = new ConcurrentHashMap<>();

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

            // Outbox 在线程池中执行；触发服务依赖 TenantContext，因此必须显式恢复并还原。
            UUID previousTenant = TenantContext.getTenantId();
            try {
                TenantContext.setTenantId(tenantId);
                List<ReadyCandidate> candidates = publishedCandidates(tenantId, targetTable);
                if (candidates.isEmpty()) {
                    log.debug("PipelineSyncRefTriggerHandler：没有已发布流水线引用目标表 {}", targetTable);
                    return;
                }
                for (ReadyCandidate candidate : candidates) {
                    triggerPipeline(candidate, event.getOccurredAt());
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

    private List<ReadyCandidate> publishedCandidates(UUID tenantId, String targetTable) {
        List<ReadyCandidate> candidates = new java.util.ArrayList<>();
        for (Dag liveDag : dagRepo.findByTenantId(tenantId)) {
            if (!Boolean.TRUE.equals(liveDag.getEnabled())) {
                readinessByVersion.keySet().removeIf(key -> key.dagId().equals(liveDag.getId()));
                continue;
            }
            if (liveDag.getPublishedVersionId() == null) {
                readinessByVersion.keySet().removeIf(key -> key.dagId().equals(liveDag.getId()));
                log.info("PipelineSyncRefTriggerHandler：流水线 {} 无已发布版本，跳过 EVENT 生产触发",
                        liveDag.getId());
                continue;
            }
            if (!"PUBLISHED".equalsIgnoreCase(liveDag.getStatus())) {
                readinessByVersion.keySet().removeIf(key -> key.dagId().equals(liveDag.getId()));
                continue;
            }
            ReadinessKey currentKey = new ReadinessKey(
                    liveDag.getId(), liveDag.getPublishedVersionId());
            readinessByVersion.keySet().removeIf(key -> key.dagId().equals(liveDag.getId())
                    && !key.equals(currentKey));
            try {
                PipelineSnapshotService.ExecutionSnapshot snapshot = pipelineSnapshotService
                        .loadExecutionSnapshot(liveDag.getPublishedVersionId(), liveDag.getId());
                Set<String> readyTaskKeys = snapshot.tasks().stream()
                        .filter(task -> task.getTaskType() == TaskType.SYNC_REF)
                        .filter(task -> targetTable.equals(task.getTargetFqn()))
                        .map(PipelineTask::getTaskKey)
                        .collect(Collectors.toSet());
                if (!readyTaskKeys.isEmpty()) {
                    candidates.add(new ReadyCandidate(liveDag, snapshot, readyTaskKeys));
                }
            } catch (BizException ex) {
                log.warn("PipelineSyncRefTriggerHandler：加载 dag {} 已发布版本失败：{}",
                        liveDag.getId(), ex.getMessage());
            }
        }
        return candidates;
    }

    private void triggerPipeline(ReadyCandidate candidate, Instant eventOccurredAt) {
        UUID dagId = candidate.liveDag().getId();
        try {
            if (!markReadyAndCheckBarrier(
                    candidate.snapshot(), candidate.readyTaskKeys(), eventOccurredAt)) {
                return;
            }
            UUID versionId = candidate.snapshot().version().getId();
            orchestrationService.triggerPipelineRun(
                    dagId, TriggerType.EVENT, RunContext.empty(TriggerType.EVENT), versionId);
            readinessByVersion.remove(new ReadinessKey(dagId, versionId));
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
    private boolean markReadyAndCheckBarrier(PipelineSnapshotService.ExecutionSnapshot snapshot,
                                             Set<String> readyTaskKeys,
                                             Instant eventOccurredAt) {
        if (readyTaskKeys == null || readyTaskKeys.isEmpty()) {
            return false;
        }
        UUID versionId = snapshot.version().getId();
        Map<String, Instant> ready = readinessByVersion.computeIfAbsent(
                new ReadinessKey(snapshot.version().getDagId(), versionId),
                ignored -> new ConcurrentHashMap<>());
        Instant readyAt = eventOccurredAt == null ? Instant.now() : eventOccurredAt;
        readyTaskKeys.forEach(taskKey -> ready.put(taskKey, readyAt));

        List<PipelineTask> tasks = snapshot.tasks();
        Map<String, PipelineTask> taskByKey = tasks.stream()
                .collect(Collectors.toMap(PipelineTask::getTaskKey, task -> task, (a, b) -> a));
        List<PipelineTaskEdge> allEdges = snapshot.edges();
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
                log.info("PipelineSyncRefTriggerHandler：dag {} version {} 等待目标节点 {} 的输入就绪，缺少 {}",
                        snapshot.version().getDagId(), versionId, targetKey, missing);
                return false;
            }
        }
        return true;
    }

    /** 流水线成功触发后清除本轮屏障，允许下一批输入重新汇合。 */
    private record ReadyCandidate(
            Dag liveDag,
            PipelineSnapshotService.ExecutionSnapshot snapshot,
            Set<String> readyTaskKeys) {}

    /** DAG 与不可变版本共同标识一次就绪屏障，便于重新发布时清理旧版本状态。 */
    private record ReadinessKey(UUID dagId, UUID versionId) {}
}
