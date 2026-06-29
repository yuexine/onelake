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
 * Pipeline v2 event handler that triggers pipelines on {@code integration.table.loaded}.
 *
 * <p><b>C4 (docs/流水线模块重设计方案.md §6.5 / §7 P1)</b>: this is the orchestration-side
 * trigger path. Modeling no longer starts DWD jobs directly; this handler is the
 * single source of ODS-event triggering.
 *
 * <p>Matching rule: pipeline contains a {@link TaskType#SYNC_REF} task whose {@code target_fqn}
 * equals the event's {@code targetTable}. On match → call
 * {@link OrchestrationService#triggerPipelineRun(UUID, TriggerType)}.
 *
 * <p>Outbox handlers run in a background thread; we set {@link TenantContext} from the event
 * payload.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PipelineSyncRefTriggerHandler implements DomainEventHandler {

    private final DagRepository dagRepo;
    private final PipelineTaskRepository taskRepo;
    private final PipelineTaskEdgeRepository edgeRepo;
    private final OrchestrationService orchestrationService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<UUID, Map<String, Instant>> readinessByDag = new ConcurrentHashMap<>();

    @Override
    public Set<String> eventTypes() {
        return Set.of(DomainEvents.INTEGRATION_TABLE_LOADED);
    }

    @Override
    public void handle(OutboxEvent event) {
        try {
            JsonNode payload = objectMapper.readTree(event.getPayload() == null ? "{}" : event.getPayload());
            String targetTable = payload.path("targetTable").asText("");
            String tenantIdRaw = payload.path("tenantId").asText("");
            if (targetTable.isBlank() || tenantIdRaw.isBlank()) {
                log.debug("PipelineSyncRefTriggerHandler skipped event {} (missing targetTable/tenantId)",
                        event.getId());
                return;
            }

            UUID tenantId;
            try {
                tenantId = UUID.fromString(tenantIdRaw);
            } catch (IllegalArgumentException e) {
                log.warn("PipelineSyncRefTriggerHandler skipped event {} (bad tenantId {})",
                        event.getId(), tenantIdRaw);
                return;
            }

            // Find pipelines containing a SYNC_REF task matching this target table
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
                log.debug("PipelineSyncRefTriggerHandler: no pipelines reference target={}", targetTable);
                return;
            }

            // Trigger each matching pipeline (under tenant context)
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
            log.error("PipelineSyncRefTriggerHandler failed for event {}: {}",
                    event.getId(), e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    private void triggerPipeline(UUID dagId, UUID tenantId, Set<String> readyTaskKeys, Instant eventOccurredAt) {
        try {
            Dag dag = dagRepo.findByIdAndTenantId(dagId, tenantId).orElse(null);
            if (dag == null) {
                log.warn("PipelineSyncRefTriggerHandler: dag {} not found for tenant {}", dagId, tenantId);
                return;
            }
            if (!Boolean.TRUE.equals(dag.getEnabled())) {
                log.debug("PipelineSyncRefTriggerHandler: dag {} disabled, skip", dagId);
                return;
            }
            if (dag.getStatus() != null
                    && !"VALIDATED".equalsIgnoreCase(dag.getStatus())
                    && !"PUBLISHED".equalsIgnoreCase(dag.getStatus())) {
                log.debug("PipelineSyncRefTriggerHandler: dag {} status={}, skip (not VALIDATED/PUBLISHED)",
                        dagId, dag.getStatus());
                return;
            }
            if (!markReadyAndCheckBarrier(dagId, readyTaskKeys, eventOccurredAt)) {
                return;
            }
            orchestrationService.triggerPipelineRun(dagId, TriggerType.EVENT);
            clearSatisfiedReadiness(dagId);
            log.info("PipelineSyncRefTriggerHandler: triggered pipeline {} after table loaded", dagId);
        } catch (BizException e) {
            log.info("PipelineSyncRefTriggerHandler: pipeline {} not triggered (biz): {}",
                    dagId, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("PipelineSyncRefTriggerHandler: pipeline {} trigger failed: {}",
                    dagId, e.getMessage());
        }
    }

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
                log.info("PipelineSyncRefTriggerHandler: dag {} readiness waiting for target {} missing inputs {}",
                        dagId, targetKey, missing);
                return false;
            }
        }
        return true;
    }

    private void clearSatisfiedReadiness(UUID dagId) {
        readinessByDag.remove(dagId);
    }
}
