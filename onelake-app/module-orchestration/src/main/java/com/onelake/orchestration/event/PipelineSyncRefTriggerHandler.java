package com.onelake.orchestration.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.outbox.DomainEvents;
import com.onelake.common.outbox.DomainEventHandler;
import com.onelake.common.outbox.OutboxEvent;
import com.onelake.orchestration.domain.entity.AssetReadiness;
import com.onelake.orchestration.domain.entity.AssetTriggerReceipt;
import com.onelake.orchestration.domain.entity.Dag;
import com.onelake.orchestration.domain.entity.PipelineSubscription;
import com.onelake.orchestration.domain.entity.PipelineTask;
import com.onelake.orchestration.domain.entity.PipelineTaskEdge;
import com.onelake.orchestration.domain.enums.EdgeLayer;
import com.onelake.orchestration.domain.enums.TaskType;
import com.onelake.orchestration.domain.enums.TriggerType;
import com.onelake.orchestration.repository.AssetReadinessRepository;
import com.onelake.orchestration.repository.AssetTriggerReceiptRepository;
import com.onelake.orchestration.repository.DagRepository;
import com.onelake.orchestration.repository.PipelineSubscriptionRepository;
import com.onelake.orchestration.repository.SchedulerLockRepository;
import com.onelake.orchestration.service.OrchestrationService;
import com.onelake.orchestration.service.PipelineSnapshotService;
import com.onelake.orchestration.service.RunContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 流水线 V2 资产事件触发器：消费资产落地事件后触发订阅的下游流水线。
 *
 * <p>这是编排模块侧的 ODS 表就绪触发路径。建模模块不再直接启动 DWD 作业；
 * ODS 事件驱动统一从这里进入编排。
 *
 * <p>匹配规则同时包含存量 {@link TaskType#SYNC_REF} 隐式订阅和
 * {@code pipeline_subscription} 显式订阅。匹配后调用
 * {@link OrchestrationService#triggerPipelineRun(UUID, TriggerType)} 的 PROD 路径。
 *
 * <p>Outbox handler 在后台线程执行，因此需要从事件载荷恢复 {@link TenantContext}。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PipelineSyncRefTriggerHandler implements DomainEventHandler {

    private static final String SOURCE_TYPE_ASSET = "ASSET";
    private static final String SOURCE_TYPE_PIPELINE = "PIPELINE";
    private static final Set<String> TRIGGERABLE_STATUSES = Set.of("VALIDATED", "PUBLISHED");
    private static final String LOCK_KEY_PREFIX = "asset-trigger:";
    private static final Duration LOCK_TTL = Duration.ofMinutes(5);

    private final DagRepository dagRepo;
    private final PipelineSubscriptionRepository subscriptionRepo;
    private final AssetReadinessRepository readinessRepo;
    private final AssetTriggerReceiptRepository triggerReceiptRepo;
    private final SchedulerLockRepository schedulerLockRepo;
    private final PipelineSnapshotService pipelineSnapshotService;
    private final OrchestrationService orchestrationService;
    private final SyncRunSucceededEventHandler legacySyncRunHandler;
    /** Handler 无请求级 ObjectMapper 注入需求，使用局部 JSON 解析器读取稳定事件载荷。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 声明本 Handler 同时消费集成资产和流水线任务产出资产事件。 */
    @Override
    public Set<String> eventTypes() {
        return Set.of(
                DomainEvents.INTEGRATION_TABLE_LOADED,
                DomainEvents.PIPELINE_TASK_LOADED);
    }

    /**
     * 统一解析事件资产和运行批次，匹配隐式/显式订阅后在租户上下文中尝试触发流水线。
     */
    @Override
    public void handle(OutboxEvent event) {
        try {
            JsonNode payload = objectMapper.readTree(event.getPayload() == null ? "{}" : event.getPayload());
            String assetFqn = firstText(payload, "targetTable", "targetFqn");
            String tenantIdRaw = payload.path("tenantId").asText("");
            if (assetFqn.isBlank() || tenantIdRaw.isBlank()) {
                log.debug("PipelineSyncRefTriggerHandler 跳过事件 {}：缺少 targetTable/targetFqn/tenantId",
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
            EventAsset eventAsset = new EventAsset(
                    event.getId(),
                    tenantId,
                    assetFqn,
                    text(payload, "batchId"),
                    text(payload, "runId"),
                    instant(payload, "logicalDate", event.getId()),
                    text(payload, "pipelineId"),
                    event.getEventType(),
                    event.getOccurredAt() == null ? Instant.now() : event.getOccurredAt());

            // Outbox 在线程池中执行；触发服务依赖 TenantContext，因此必须显式恢复并还原。
            UUID previousTenant = TenantContext.getTenantId();
            try {
                TenantContext.setTenantId(tenantId);
                List<ReadyCandidate> candidates = publishedCandidates(eventAsset);
                if (candidates.isEmpty()) {
                    log.debug("PipelineSyncRefTriggerHandler：没有已发布流水线订阅资产 {}", assetFqn);
                } else {
                    for (ReadyCandidate candidate : candidates) {
                        triggerPipeline(candidate, eventAsset);
                    }
                }
                if (DomainEvents.INTEGRATION_TABLE_LOADED.equals(event.getEventType())) {
                    legacySyncRunHandler.handle(event);
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

    private List<ReadyCandidate> publishedCandidates(EventAsset eventAsset) {
        Map<UUID, List<PipelineSubscription>> subscriptionsByDag = matchingSubscriptions(eventAsset)
                .stream()
                .collect(Collectors.groupingBy(
                        PipelineSubscription::getDagId,
                        LinkedHashMap::new,
                        Collectors.toList()));
        List<ReadyCandidate> candidates = new ArrayList<>();
        for (Dag liveDag : dagRepo.findByTenantId(eventAsset.tenantId())) {
            if (!Boolean.TRUE.equals(liveDag.getEnabled())) {
                readinessRepo.deleteByDagId(liveDag.getId());
                continue;
            }
            if (liveDag.getPublishedVersionId() == null) {
                readinessRepo.deleteByDagId(liveDag.getId());
                log.info("PipelineSyncRefTriggerHandler：流水线 {} 无已发布版本，跳过 EVENT 生产触发",
                        liveDag.getId());
                continue;
            }
            if (liveDag.getStatus() == null
                    || !TRIGGERABLE_STATUSES.contains(liveDag.getStatus().toUpperCase(Locale.ROOT))) {
                readinessRepo.deleteByDagId(liveDag.getId());
                continue;
            }
            try {
                PipelineSnapshotService.ExecutionSnapshot snapshot = pipelineSnapshotService
                        .loadExecutionSnapshot(liveDag.getPublishedVersionId(), liveDag.getId());
                Map<String, String> readyInputs = snapshot.tasks().stream()
                        .filter(task -> task.getTaskType() == TaskType.SYNC_REF)
                        .filter(task -> eventAsset.assetFqn().equals(task.getTargetFqn()))
                        .collect(Collectors.toMap(
                                PipelineTask::getTaskKey,
                                PipelineTask::getTargetFqn,
                                (left, right) -> left,
                                LinkedHashMap::new));
                Set<SourceMatch> sourceMatches = new LinkedHashSet<>();
                if (!readyInputs.isEmpty()) {
                    sourceMatches.add(new SourceMatch(SOURCE_TYPE_ASSET, eventAsset.assetFqn()));
                }
                List<PipelineSubscription> explicit = subscriptionsByDag.getOrDefault(
                        liveDag.getId(), List.of());
                explicit.forEach(subscription -> sourceMatches.add(new SourceMatch(
                        subscription.getSourceType().toUpperCase(Locale.ROOT),
                        subscription.getSourceRef())));
                if (!explicit.isEmpty() && readyInputs.isEmpty()) {
                    for (PipelineSubscription subscription : explicit) {
                        readyInputs.put(subscriptionTaskKey(subscription), eventAsset.assetFqn());
                    }
                }
                if (!readyInputs.isEmpty()) {
                    candidates.add(new ReadyCandidate(
                            liveDag,
                            snapshot,
                            Map.copyOf(readyInputs),
                            Set.copyOf(sourceMatches)));
                }
            } catch (BizException ex) {
                log.warn("PipelineSyncRefTriggerHandler：加载 dag {} 已发布版本失败：{}",
                        liveDag.getId(), ex.getMessage());
            }
        }
        return candidates;
    }

    private List<PipelineSubscription> matchingSubscriptions(EventAsset eventAsset) {
        Map<String, PipelineSubscription> matches = new LinkedHashMap<>();
        addSubscriptions(matches, subscriptionRepo
                .findByTenantIdAndSourceTypeAndSourceRefAndEnabledTrue(
                        eventAsset.tenantId(), SOURCE_TYPE_ASSET, eventAsset.assetFqn()));
        if (DomainEvents.PIPELINE_TASK_LOADED.equals(eventAsset.eventType())
                && !eventAsset.pipelineId().isBlank()) {
            addSubscriptions(matches, subscriptionRepo
                    .findByTenantIdAndSourceTypeAndSourceRefAndEnabledTrue(
                            eventAsset.tenantId(), SOURCE_TYPE_PIPELINE, eventAsset.pipelineId()));
        }
        return List.copyOf(matches.values());
    }

    private void addSubscriptions(Map<String, PipelineSubscription> target,
                                  List<PipelineSubscription> subscriptions) {
        if (subscriptions == null) {
            return;
        }
        for (PipelineSubscription subscription : subscriptions) {
            String key = subscription.getId() == null
                    ? subscription.getDagId() + "|" + subscription.getSourceType()
                        + "|" + subscription.getSourceRef()
                    : subscription.getId().toString();
            target.putIfAbsent(key, subscription);
        }
    }

    private void triggerPipeline(ReadyCandidate candidate, EventAsset eventAsset) {
        UUID dagId = candidate.liveDag().getId();
        String lockKey = LOCK_KEY_PREFIX + dagId;
        String lockHolder = UUID.randomUUID().toString();
        boolean acquired;
        try {
            acquired = schedulerLockRepo.acquire(
                    lockKey, lockHolder, Instant.now().plus(LOCK_TTL)) == 1;
        } catch (RuntimeException e) {
            throw new AssetTriggerRetryableException(
                    "流水线 " + dagId + " 获取资产触发锁失败", e);
        }
        if (!acquired) {
            throw new AssetTriggerRetryableException(
                    "流水线 " + dagId + " 正由其他副本处理资产触发");
        }
        try {
            List<TriggerIdentity> identities = candidate.sourceMatches().stream()
                    .map(source -> triggerIdentity(
                            eventAsset, candidate.snapshot().version().getId(), source))
                    .toList();
            List<TriggerIdentity> pendingIdentities = identities.stream()
                    .filter(identity -> !triggerReceiptRepo.existsByDagIdAndTriggerKey(
                            dagId, identity.triggerKey()))
                    .toList();
            if (pendingIdentities.isEmpty()) {
                log.info("PipelineSyncRefTriggerHandler：流水线 {} 已处理当前来源与业务窗口，跳过重复事件",
                        dagId);
                return;
            }
            if (!markReadyAndCheckBarrier(
                    candidate.snapshot(), candidate.readyInputs(), eventAsset)) {
                saveReceipts(candidate, eventAsset, pendingIdentities, "READY", null);
                return;
            }
            UUID versionId = candidate.snapshot().version().getId();
            RunContext runContext = eventAsset.logicalDate() == null
                    ? RunContext.empty(TriggerType.EVENT)
                    : new RunContext(
                            eventAsset.logicalDate(), null, null, null,
                            null, null, TriggerType.EVENT);
            UUID runId = orchestrationService.triggerPipelineRun(
                    dagId,
                    TriggerType.EVENT,
                    runContext,
                    versionId,
                    runTriggerKey(versionId, eventAsset));
            saveReceipts(candidate, eventAsset, pendingIdentities, "TRIGGERED", runId);
            readinessRepo.deleteByDagId(dagId);
            log.info("PipelineSyncRefTriggerHandler：资产 {} 就绪后已触发流水线 {}",
                    eventAsset.assetFqn(), dagId);
        } catch (BizException e) {
            log.info("PipelineSyncRefTriggerHandler：流水线 {} 未触发，业务原因：{}",
                    dagId, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("PipelineSyncRefTriggerHandler：流水线 {} 触发失败：{}",
                    dagId, e.getMessage());
            throw new AssetTriggerRetryableException(
                    "流水线 " + dagId + " 资产触发或回执持久化失败", e);
        } finally {
            try {
                schedulerLockRepo.release(lockKey, lockHolder);
            } catch (RuntimeException e) {
                log.warn("PipelineSyncRefTriggerHandler：流水线 {} 释放资产触发锁失败：{}",
                        dagId, e.getMessage());
            }
        }
    }

    /**
     * 记录本次已就绪 SYNC_REF，并检查其共同下游的所有同步输入是否均已到达。
     *
     * <p>单输入目标无需等待；多输入目标只有缺失集合为空才允许触发整条流水线。
     */
    private boolean markReadyAndCheckBarrier(PipelineSnapshotService.ExecutionSnapshot snapshot,
                                             Map<String, String> readyInputs,
                                             EventAsset eventAsset) {
        if (readyInputs == null || readyInputs.isEmpty()) {
            return false;
        }
        UUID versionId = snapshot.version().getId();
        UUID dagId = snapshot.version().getDagId();
        List<AssetReadiness> updates = readyInputs.entrySet().stream()
                .map(entry -> readiness(
                        eventAsset, dagId, entry.getKey(), entry.getValue()))
                .toList();
        readinessRepo.saveAllAndFlush(updates);

        Instant versionCreatedAt = snapshot.version().getCreatedAt();
        List<AssetReadiness> persisted = readinessRepo.findByDagId(dagId);
        List<AssetReadiness> stale = persisted.stream()
                .filter(readiness -> !eventAsset.tenantId().equals(readiness.getTenantId())
                        || (versionCreatedAt != null
                            && readiness.getReadyAt() != null
                            && readiness.getReadyAt().isBefore(versionCreatedAt)))
                .toList();
        if (!stale.isEmpty()) {
            readinessRepo.deleteAllInBatch(stale);
        }
        Map<String, AssetReadiness> readinessByTask = persisted.stream()
                .filter(readiness -> eventAsset.tenantId().equals(readiness.getTenantId()))
                .filter(readiness -> versionCreatedAt == null
                        || readiness.getReadyAt() == null
                        || !readiness.getReadyAt().isBefore(versionCreatedAt))
                .collect(Collectors.toMap(
                        AssetReadiness::getTaskKey,
                        readiness -> readiness,
                        (left, right) -> left));
        if (!readinessByTask.keySet().containsAll(readyInputs.keySet())) {
            return false;
        }

        List<PipelineTask> tasks = snapshot.tasks();
        Map<String, PipelineTask> taskByKey = tasks.stream()
                .collect(Collectors.toMap(PipelineTask::getTaskKey, task -> task, (a, b) -> a));
        List<PipelineTaskEdge> allEdges = snapshot.edges();
        List<PipelineTaskEdge> edges = allEdges.stream()
                .filter(edge -> edge.getEdgeLayer() == EdgeLayer.PIPELINE)
                .toList();

        Set<String> touchedTargets = edges.stream()
                .filter(edge -> readyInputs.containsKey(edge.getSourceKey()))
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
                    .filter(edge -> !readinessMatchesWindow(
                            readinessByTask.get(edge.getSourceKey()), edge, eventAsset))
                    .map(PipelineTaskEdge::getSourceKey)
                    .toList();
            if (!missing.isEmpty()) {
                log.info("PipelineSyncRefTriggerHandler：dag {} version {} 等待目标节点 {} 的输入就绪，缺少 {}",
                        snapshot.version().getDagId(), versionId, targetKey, missing);
                return false;
            }
        }
        return true;
    }

    private boolean readinessMatchesWindow(AssetReadiness readiness,
                                           PipelineTaskEdge edge,
                                           EventAsset eventAsset) {
        if (readiness == null) {
            return false;
        }
        String policy = edge.getFreshnessPolicy() == null
                ? "LATEST"
                : edge.getFreshnessPolicy().trim().toUpperCase(Locale.ROOT);
        return switch (policy) {
            case "SAME_BATCH" -> eventAsset.effectiveBatchId() != null
                    && eventAsset.effectiveBatchId().equals(readiness.getBatchId());
            case "SAME_FRESHNESS_WINDOW" -> eventAsset.logicalDate() != null
                    && eventAsset.logicalDate().toString().equals(readiness.getFreshnessWindow());
            default -> true;
        };
    }

    private AssetReadiness readiness(EventAsset eventAsset,
                                     UUID dagId,
                                     String taskKey,
                                     String assetFqn) {
        AssetReadiness readiness = new AssetReadiness();
        readiness.setTenantId(eventAsset.tenantId());
        readiness.setDagId(dagId);
        readiness.setTaskKey(taskKey);
        readiness.setAssetFqn(assetFqn);
        readiness.setBatchId(eventAsset.effectiveBatchId());
        readiness.setFreshnessWindow(eventAsset.logicalDate() == null
                ? null
                : eventAsset.logicalDate().toString());
        readiness.setReadyAt(eventAsset.occurredAt());
        return readiness;
    }

    private AssetTriggerReceipt receipt(ReadyCandidate candidate,
                                        EventAsset eventAsset,
                                        TriggerIdentity identity,
                                        String status,
                                        UUID jobRunId) {
        AssetTriggerReceipt receipt = new AssetTriggerReceipt();
        receipt.setTenantId(eventAsset.tenantId());
        receipt.setDagId(candidate.liveDag().getId());
        receipt.setTriggerKey(identity.triggerKey());
        receipt.setEventId(eventAsset.eventId());
        receipt.setSourceType(identity.sourceType());
        receipt.setSourceRef(identity.sourceRef());
        receipt.setBatchId(eventAsset.effectiveBatchId());
        receipt.setLogicalDate(eventAsset.logicalDate());
        receipt.setPipelineVersionId(candidate.snapshot().version().getId());
        receipt.setJobRunId(jobRunId);
        receipt.setStatus(status);
        return receipt;
    }

    private void saveReceipts(ReadyCandidate candidate,
                              EventAsset eventAsset,
                              List<TriggerIdentity> identities,
                              String status,
                              UUID jobRunId) {
        triggerReceiptRepo.saveAllAndFlush(identities.stream()
                .map(identity -> receipt(
                        candidate, eventAsset, identity, status, jobRunId))
                .toList());
    }

    private TriggerIdentity triggerIdentity(EventAsset eventAsset,
                                            UUID versionId,
                                            SourceMatch source) {
        String window = triggerWindow(eventAsset);
        return new TriggerIdentity(
                sha256(versionId + "|" + source.sourceType() + "|" + source.sourceRef()
                        + "|" + window),
                source.sourceType(),
                source.sourceRef(),
                window);
    }

    private String runTriggerKey(UUID versionId, EventAsset eventAsset) {
        return sha256(versionId + "|EVENT|" + triggerWindow(eventAsset));
    }

    private String triggerWindow(EventAsset eventAsset) {
        if (eventAsset.logicalDate() != null) {
            return "logical:" + eventAsset.logicalDate();
        }
        if (!eventAsset.batchId().isBlank()) {
            return "batch:" + eventAsset.batchId();
        }
        if (!eventAsset.runId().isBlank()) {
            return "run:" + eventAsset.runId();
        }
        return "event:" + eventAsset.eventId();
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private String subscriptionTaskKey(PipelineSubscription subscription) {
        String identity = subscription.getDagId() + "|" + subscription.getSourceType()
                + "|" + subscription.getSourceRef();
        return "subscription:" + UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
    }

    private String firstText(JsonNode payload, String first, String second) {
        String value = text(payload, first);
        return value.isBlank() ? text(payload, second) : value;
    }

    private String text(JsonNode payload, String field) {
        return payload.path(field).asText("").trim();
    }

    private Instant instant(JsonNode payload, String field, UUID eventId) {
        String value = text(payload, field);
        if (value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            log.warn("PipelineSyncRefTriggerHandler 事件 {} 的 {} 非法：{}",
                    eventId, field, value);
            return null;
        }
    }

    /** 一条匹配流水线及当前事件命中的持久化就绪输入。 */
    private record ReadyCandidate(
            Dag liveDag,
            PipelineSnapshotService.ExecutionSnapshot snapshot,
            Map<String, String> readyInputs,
            Set<SourceMatch> sourceMatches) {}

    /** 当前事件实际命中的隐式或显式订阅来源。 */
    private record SourceMatch(String sourceType, String sourceRef) {}

    /** 从两类事件统一解析出的资产触发上下文。 */
    private record EventAsset(
            UUID eventId,
            UUID tenantId,
            String assetFqn,
            String batchId,
            String runId,
            Instant logicalDate,
            String pipelineId,
            String eventType,
            Instant occurredAt) {

        String effectiveBatchId() {
            String value = batchId.isBlank() ? runId : batchId;
            if (value.isBlank()) {
                return null;
            }
            return value.length() <= 128 ? value : value.substring(0, 128);
        }
    }

    /** 持久化回执使用的规范化来源与业务窗口。 */
    private record TriggerIdentity(
            String triggerKey,
            String sourceType,
            String sourceRef,
            String window) {}

    /** 锁竞争或数据库锁访问失败时抛出，使 Redis Stream 保留消息等待重试。 */
    private static final class AssetTriggerRetryableException extends RuntimeException {

        private AssetTriggerRetryableException(String message) {
            super(message);
        }

        private AssetTriggerRetryableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
