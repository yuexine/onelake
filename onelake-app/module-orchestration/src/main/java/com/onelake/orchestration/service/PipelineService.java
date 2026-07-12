package com.onelake.orchestration.service;

import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.util.JsonUtil;
import com.onelake.common.outbox.DomainEvents;
import com.onelake.common.outbox.OutboxPublisher;
import com.onelake.common.system.repository.TenantRepository;
import com.onelake.orchestration.domain.entity.Dag;
import com.onelake.orchestration.domain.entity.PipelineTask;
import com.onelake.orchestration.domain.entity.PipelineTaskEdge;
import com.onelake.orchestration.domain.entity.PipelineVersion;
import com.onelake.orchestration.domain.enums.EdgeLayer;
import com.onelake.orchestration.domain.enums.TaskCompileStatus;
import com.onelake.orchestration.domain.enums.TaskType;
import com.onelake.orchestration.dto.OdsDwdTemplateRequest;
import com.onelake.orchestration.dto.OdsDwdTemplateResult;
import com.onelake.orchestration.dto.PipelineTaskDTO;
import com.onelake.orchestration.dto.PipelineTaskEdgeDTO;
import com.onelake.orchestration.dto.PipelineTaskEdgeRequest;
import com.onelake.orchestration.dto.PipelineTaskRequest;
import com.onelake.orchestration.dto.PipelineValidationResult;
import com.onelake.orchestration.dto.PipelineValidationResult.GraphError;
import com.onelake.orchestration.dto.PipelineValidationResult.TaskValidation;
import com.onelake.orchestration.dto.PipelineCompileResult;
import com.onelake.orchestration.dto.TaskRunDTO;
import com.onelake.orchestration.repository.DagRepository;
import com.onelake.orchestration.repository.JobRunRepository;
import com.onelake.orchestration.repository.PipelineTaskEdgeRepository;
import com.onelake.orchestration.repository.PipelineTaskRepository;
import com.onelake.orchestration.repository.PipelineParamRepository;
import com.onelake.orchestration.repository.TaskRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 流水线 V2 的实体读写与校验服务。
 *
 * <p>该服务是统一流水线编辑器的后端读写入口，负责任务、边、发布状态和模板化创建；
 * 图编译与 C1 约束仍由 {@link PipelineCompileService} 统一处理。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PipelineService {

    /** 统一流水线 DAG 的控制面引擎标识。 */
    private static final String PIPELINE_ENGINE = "SPARK";
    private static final String SPARK_SQL_ENGINE = "SPARK_SQL";
    private static final String PYSPARK_ENGINE = "PYSPARK";
    private static final String DEFAULT_RESOURCE_GROUP = "spark-default";
    private static final String DEFAULT_COMPUTE_PROFILE = "spark-small";

    private static final Set<String> VALID_TASK_TYPES = Arrays.stream(TaskType.values())
            .map(TaskType::name)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    private final DagRepository dagRepo;
    private final PipelineTaskRepository taskRepo;
    private final PipelineTaskEdgeRepository edgeRepo;
    private final PipelineParamRepository paramRepo;
    private final TaskRunRepository taskRunRepo;
    private final JobRunRepository runRepo;
    private final PipelineCompileService compileService;
    private final PipelineSnapshotService snapshotService;
    private final org.springframework.beans.factory.ObjectProvider<OutboxPublisher> outboxPublisher;
    private final TenantRepository tenantRepo;

    @Value("${onelake.orchestration.publish-approval.enabled:false}")
    private boolean publishApprovalEnabled;

    // ---------- 流水线（dag） ----------

    /** 供控制台按服务端实际配置决定是否展示发布审批态。 */
    public boolean isPublishApprovalEnabled() {
        return publishApprovalEnabled;
    }

    /** 在当前租户边界内读取流水线。 */
    @Transactional
    public Dag getPipeline(UUID dagId) {
        return dagRepo.findByIdAndTenantId(dagId, requireTenant())
                .orElseThrow(() -> new BizException(40400, "Pipeline 不存在"));
    }

    /** 创建带默认 Spark 资源契约的流水线草稿。 */
    @Transactional
    public Dag createPipeline(String name, String pipelineKind) {
        UUID tenantId = requireTenant();
        Dag dag = new Dag();
        dag.setTenantId(tenantId);
        dag.setName(name.trim());
        dag.setDagsterJob("onelake_pipeline_run");
        dag.setDefinition("{}");
        dag.setEnabled(true);
        dag.setVersion(1);
        dag.setPipelineKind(StringUtils.hasText(pipelineKind) ? pipelineKind : "BLANK");
        dag.setStatus("DRAFT");
        dag.setEngine(PIPELINE_ENGINE);
        dag.setResourceGroup(DEFAULT_RESOURCE_GROUP);
        dag.setComputeProfile(DEFAULT_COMPUTE_PROFILE);
        return dagRepo.save(dag);
    }

    /**
     * P4-D 发布状态机：DRAFT → VALIDATED → PUBLISHED。
     *
     * <p>状态流转约束：
     * <ul>
     *   <li>DRAFT → VALIDATED：必须先通过 {@link #validate(UUID)} 校验。</li>
     *   <li>VALIDATED → PUBLISHED：发布 {@code pipeline.published} Outbox 事件。</li>
     *   <li>任意状态 → DRAFT：允许回退。</li>
     *   <li>其他流转一律拒绝。</li>
     * </ul>
     */
    @Transactional
    public Dag updatePipelineStatus(UUID dagId, String targetStatusRaw) {
        String target = targetStatusRaw == null ? "" : targetStatusRaw.toUpperCase();
        if (!Set.of("DRAFT", "VALIDATED", "PUBLISHED").contains(target)) {
            throw new BizException(40020, "非法 pipeline status: " + targetStatusRaw);
        }
        UUID tenantId = requireTenant();
        if ("PUBLISHED".equals(target)) {
            lockTenant(tenantId);
        }
        Dag dag = getPipelineForUpdate(dagId, tenantId);
        String current = dag.getStatus() == null ? "DRAFT" : dag.getStatus().toUpperCase();
        if (target.equals(current)) {
            if ("PUBLISHED".equals(target)
                    && (dag.getPublishedVersionId() == null
                        || Boolean.TRUE.equals(dag.getHasUnpublishedChanges()))) {
                PipelineValidationResult result = validate(dagId);
                if (!result.valid()) {
                    throw new BizException(40070, "无法重新发布：当前草稿校验未通过");
                }
                if (publishApprovalEnabled) {
                    submitPublishApproval(dag);
                    return dag;
                }
                PipelineVersion publishedVersion = snapshotService.publishSnapshot(dagId);
                emitPipelinePublishedEvent(dag, publishedVersion.getId());
            }
            return dag;
        }

        // 执行状态流转约束。
        if ("VALIDATED".equals(target)) {
            PipelineValidationResult result = validate(dagId);
            if (!result.valid()) {
                throw new BizException(40070, "无法设为 VALIDATED：校验未通过（"
                        + result.taskResults().stream().filter(t -> !t.valid()).count() + " 个任务错误 / "
                        + result.graphErrors().size() + " 个图错误）");
            }
        } else if ("PUBLISHED".equals(target) && !"VALIDATED".equals(current)) {
            throw new BizException(40071, "无法直接从 " + current + " 发布到 PUBLISHED：必须先 VALIDATED");
        }

        if ("PUBLISHED".equals(target) && publishApprovalEnabled) {
            submitPublishApproval(dag);
            log.info("流水线 {} 已提交发布审批，主状态保持 {}", dag.getId(), current);
            return dag;
        }

        dag.setStatus(target);
        dag.setVersion((dag.getVersion() == null ? 0 : dag.getVersion()) + 1);
        dag = dagRepo.save(dag);

        // 首次进入 PUBLISHED 时发布流水线发布事件。
        if ("PUBLISHED".equals(target)) {
            PipelineVersion publishedVersion = snapshotService.publishSnapshot(dagId);
            emitPipelinePublishedEvent(dag, publishedVersion.getId());
        }
        log.info("流水线 {} 状态流转：{} → {} (v{})",
                dag.getId(), current, target, dag.getVersion());
        return dag;
    }

    /**
     * 消费 security 审批结果后完成发布门控。
     *
     * <p>审批通过只发布审批时 checksum 对应的当前草稿，避免旧审批误发布后续编辑；
     * 审批拒绝不改变主状态机，拒绝原因由 security.approval_request.comment 持久化。
     */
    @Transactional
    public Dag handlePublishApprovalDecision(UUID dagId,
                                             String approvedChecksum,
                                             boolean approved,
                                             String reason) {
        UUID tenantId = requireTenant();
        lockTenant(tenantId);
        Dag dag = dagRepo.findByIdForUpdate(dagId)
                .filter(candidate -> tenantId.equals(candidate.getTenantId()))
                .orElseThrow(() -> new BizException(40400, "Pipeline 不存在"));
        if (!approved) {
            log.info("流水线 {} 发布审批被拒绝，保持状态 {}，原因={}", dagId, dag.getStatus(), reason);
            return dag;
        }

        PipelineSnapshotService.SnapshotPayload currentSnapshot = snapshotService.snapshot(dagId);
        if (!java.util.Objects.equals(approvedChecksum, currentSnapshot.checksum())) {
            log.warn("流水线 {} 发布审批对应内容已过期，保持状态 {}：approvedChecksum={} currentChecksum={}",
                    dagId, dag.getStatus(), approvedChecksum, currentSnapshot.checksum());
            return dag;
        }
        if (!"VALIDATED".equalsIgnoreCase(dag.getStatus())
                && !("PUBLISHED".equalsIgnoreCase(dag.getStatus())
                    && (dag.getPublishedVersionId() == null
                        || Boolean.TRUE.equals(dag.getHasUnpublishedChanges())))) {
            log.warn("流水线 {} 当前状态 {} 不接受发布审批结果，跳过", dagId, dag.getStatus());
            return dag;
        }

        String previous = dag.getStatus();
        dag.setStatus("PUBLISHED");
        dag.setVersion((dag.getVersion() == null ? 0 : dag.getVersion()) + 1);
        dag = dagRepo.save(dag);
        PipelineVersion publishedVersion = snapshotService.publishSnapshot(dagId, currentSnapshot);
        emitPipelinePublishedEvent(dag, publishedVersion.getId());
        log.info("流水线 {} 审批通过后完成发布：{} → PUBLISHED (v{})",
                dagId, previous, dag.getVersion());
        return dag;
    }

    /** 发送只包含快照摘要的审批申请，不把完整不可变快照复制进审批库。 */
    private void submitPublishApproval(Dag dag) {
        OutboxPublisher publisher = outboxPublisher.getIfAvailable();
        if (publisher == null) {
            throw new BizException(50320, "审批服务暂不可用，请稍后重试");
        }
        UUID applicantId = TenantContext.getUserId();
        if (applicantId == null) {
            throw new BizException(40100, "用户上下文缺失");
        }
        PipelineSnapshotService.SnapshotPayload snapshot = snapshotService.snapshot(dag.getId());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestType", "PUBLISH");
        payload.put("targetRef", dag.getId().toString());
        payload.put("tenantId", dag.getTenantId().toString());
        payload.put("applicantId", applicantId.toString());
        payload.put("applicantName", TenantContext.getUsername());
        payload.put("pipelineName", dag.getName());
        payload.put("pipelineKind", dag.getPipelineKind());
        payload.put("dagVersion", dag.getVersion());
        payload.put("snapshotChecksum", snapshot.checksum());
        payload.put("snapshotBytes", snapshot.json().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        payload.put("taskCount", taskRepo.findByDagIdOrderByCreatedAtAsc(dag.getId()).size());
        publisher.publish(DomainEvents.PIPELINE_PUBLISH_APPROVAL_REQUESTED, dag.getId().toString(), payload);
    }

    /** 发布流水线版本事件，携带目标资产集合供下游目录/血缘消费者处理。 */
    private void emitPipelinePublishedEvent(Dag dag, UUID versionId) {
        OutboxPublisher publisher = outboxPublisher.getIfAvailable();
        if (publisher == null) {
            log.warn("OutboxPublisher 不可用，跳过 pipeline.published 事件，pipelineId={}",
                    dag.getId());
            return;
        }
        java.util.Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("pipelineId", dag.getId().toString());
        payload.put("tenantId", dag.getTenantId() == null ? null : dag.getTenantId().toString());
        payload.put("version", dag.getVersion());
        payload.put("versionId", versionId == null ? null : versionId.toString());
        payload.put("pipelineKind", dag.getPipelineKind() == null ? "BLANK" : dag.getPipelineKind());
        payload.put("publishedBy", com.onelake.common.context.TenantContext.getUserId() == null
                ? null : com.onelake.common.context.TenantContext.getUserId().toString());
        payload.put("publishedAt", java.time.Instant.now().toString());
        // 目标表列表 targetFqns 只收集节点上声明过的非空目标表，并做去重。
        List<String> targets = taskRepo.findByDagIdOrderByCreatedAtAsc(dag.getId()).stream()
                .map(PipelineTask::getTargetFqn)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        payload.put("targetFqns", targets);
        payload.put("taskCount", taskRepo.findByDagIdOrderByCreatedAtAsc(dag.getId()).size());
        publisher.publish(DomainEvents.PIPELINE_PUBLISHED, dag.getId().toString(), payload);
    }

    // ---------- 节点 ----------

    /** 按稳定创建顺序返回流水线节点。 */
    @Transactional
    public List<PipelineTaskDTO> listTasks(UUID dagId) {
        getPipeline(dagId); // 先通过流水线查询完成租户隔离。
        return taskRepo.findByDagIdOrderByCreatedAtAsc(dagId).stream()
                .map(PipelineTaskDTO::of)
                .toList();
    }

    /** 创建节点并校验 taskKey 唯一性、类型和执行引擎契约。 */
    @Transactional
    public PipelineTaskDTO createTask(UUID dagId, PipelineTaskRequest req) {
        Dag dag = getPipelineForUpdate(dagId, requireTenant());
        validateTaskRequest(req, true);
        if (taskRepo.findByDagIdAndTaskKey(dagId, req.taskKey()).isPresent()) {
            throw new BizException(40901, "task_key 已存在: " + req.taskKey());
        }
        PipelineTask t = new PipelineTask();
        t.setTenantId(requireTenant());
        t.setDagId(dagId);
        t.setTaskKey(req.taskKey());
        t.setTaskType(TaskType.valueOf(req.taskType()));
        t.setCategory(t.getTaskType().category());
        t.setName(req.name() == null ? req.taskKey() : req.name());
        t.setEngine(normalizeTaskEngine(req.taskType(), req.engine()));
        t.setTargetFqn(req.targetFqn());
        t.setModelId(req.modelId());
        t.setSyncTaskId(req.syncTaskId());
        t.setConfig(serializeConfig(req));
        t.setPositionX(req.positionX());
        t.setPositionY(req.positionY());
        t = taskRepo.save(t);
        markUnpublishedChanges(dag);
        log.info("流水线 {} 已创建节点：key={} type={}", dagId, t.getTaskKey(), t.getTaskType());
        return PipelineTaskDTO.of(t);
    }

    /** 更新节点可编辑字段，并重置编译状态等待重新校验。 */
    @Transactional
    public PipelineTaskDTO updateTask(UUID dagId, String taskKey, PipelineTaskRequest req) {
        Dag dag = getPipelineForUpdate(dagId, requireTenant());
        PipelineTask t = taskRepo.findByDagIdAndTaskKey(dagId, taskKey)
                .orElseThrow(() -> new BizException(40400, "task 不存在: " + taskKey));
        validateTaskRequest(req, false);
        if (req.name() != null) t.setName(req.name());
        if (StringUtils.hasText(req.engine())) t.setEngine(normalizeTaskEngine(t.getTaskType().name(), req.engine()));
        if (req.targetFqn() != null) t.setTargetFqn(req.targetFqn());
        if (req.modelId() != null) t.setModelId(req.modelId());
        if (req.syncTaskId() != null) t.setSyncTaskId(req.syncTaskId());
        if (req.config() != null) t.setConfig(serializeConfig(req));
        if (req.positionX() != null) t.setPositionX(req.positionX());
        if (req.positionY() != null) t.setPositionY(req.positionY());
        // 节点被编辑后重置编译态，等待下一次校验重新计算。
        t.setCompileStatus(com.onelake.orchestration.domain.enums.TaskCompileStatus.DRAFT);
        t.setCompileError(null);
        t.setExecutable(false);
        t.setUpdatedAt(Instant.now());
        t = taskRepo.save(t);
        markUnpublishedChanges(dag);
        return PipelineTaskDTO.of(t);
    }

    /** 删除节点及所有入边/出边，避免图中残留悬空引用。 */
    @Transactional
    public void deleteTask(UUID dagId, String taskKey) {
        Dag dag = getPipelineForUpdate(dagId, requireTenant());
        PipelineTask t = taskRepo.findByDagIdAndTaskKeyForUpdate(dagId, taskKey)
                .orElseThrow(() -> new BizException(40400, "task 不存在: " + taskKey));
        // pipeline_param 通过稳定 task_key 关联节点而非外键，必须在同一事务显式清理。
        paramRepo.deleteByTenantIdAndDagIdAndTaskKeyAndScope(
                dag.getTenantId(), dagId, taskKey, "TASK");
        taskRepo.delete(t);
        // 级联删除引用该 task_key 的边，避免留下悬空引用。
        edgeRepo.findByDagId(dagId).stream()
                .filter(e -> e.getSourceKey().equals(taskKey) || e.getTargetKey().equals(taskKey))
                .forEach(edgeRepo::delete);
        markUnpublishedChanges(dag);
    }

    // ---------- 边 ----------

    /** 返回流水线全部数据流/执行依赖边。 */
    @Transactional
    public List<PipelineTaskEdgeDTO> listEdges(UUID dagId) {
        getPipeline(dagId);
        return edgeRepo.findByDagId(dagId).stream()
                .map(PipelineTaskEdgeDTO::of)
                .toList();
    }

    /** 创建边并补齐端口、输入别名、触发和新鲜度默认策略。 */
    @Transactional
    public PipelineTaskEdgeDTO createEdge(UUID dagId, PipelineTaskEdgeRequest req) {
        Dag dag = getPipelineForUpdate(dagId, requireTenant());
        if (!StringUtils.hasText(req.sourceKey()) || !StringUtils.hasText(req.targetKey())) {
            throw new BizException(40020, "edge sourceKey/targetKey 不能为空");
        }
        if (req.sourceKey().equals(req.targetKey())) {
            throw new BizException(40021, "edge 不能形成自环");
        }
        PipelineTaskEdge e = new PipelineTaskEdge();
        e.setTenantId(requireTenant());
        e.setDagId(dagId);
        e.setSourceKey(req.sourceKey());
        e.setTargetKey(req.targetKey());
        e.setEdgeLayer(StringUtils.hasText(req.edgeLayer())
                ? EdgeLayer.valueOf(req.edgeLayer()) : EdgeLayer.PIPELINE);
        e.setSourcePort(req.sourcePort() == null ? "out" : req.sourcePort());
        e.setTargetPort(req.targetPort() == null ? "in" : req.targetPort());
        e.setSourceOutput(StringUtils.hasText(req.sourceOutput()) ? req.sourceOutput() : e.getSourcePort());
        e.setTargetInput(StringUtils.hasText(req.targetInput()) ? req.targetInput() : e.getTargetPort());
        e.setAssetFqn(StringUtils.hasText(req.assetFqn()) ? req.assetFqn().trim() : null);
        e.setInputAlias(StringUtils.hasText(req.inputAlias()) ? req.inputAlias().trim() : null);
        e.setJoinRole(StringUtils.hasText(req.joinRole()) ? req.joinRole().trim() : e.getTargetInput());
        e.setTriggerPolicy(StringUtils.hasText(req.triggerPolicy()) ? req.triggerPolicy().trim() : "ALL_SUCCEEDED");
        e.setFreshnessPolicy(StringUtils.hasText(req.freshnessPolicy())
                ? req.freshnessPolicy().trim() : defaultFreshnessPolicy(e.getTargetInput()));
        e.setAuto(Boolean.TRUE.equals(req.auto()));
        e = edgeRepo.save(e);
        markUnpublishedChanges(dag);
        return PipelineTaskEdgeDTO.of(e);
    }

    private String defaultFreshnessPolicy(String targetInput) {
        if ("left".equalsIgnoreCase(targetInput) || "right".equalsIgnoreCase(targetInput)) {
            return "SAME_FRESHNESS_WINDOW";
        }
        return "LATEST";
    }

    /** 删除 sourceKey -> targetKey 的确定边；不存在时幂等返回。 */
    @Transactional
    public void deleteEdge(UUID dagId, String sourceKey, String targetKey) {
        Dag dag = getPipelineForUpdate(dagId, requireTenant());
        edgeRepo.findByDagId(dagId).stream()
                .filter(e -> e.getSourceKey().equals(sourceKey) && e.getTargetKey().equals(targetKey))
                .findFirst()
                .ifPresent(edge -> {
                    edgeRepo.delete(edge);
                    markUnpublishedChanges(dag);
                });
    }

    // ---------- 校验 ----------

    /**
     * L1 + L2 校验入口（§6.7）。
     *
     * <p>该方法调用编译服务执行 C1 等约束，再把编译结果转换为接口响应结构。
     */
    @Transactional
    public PipelineValidationResult validate(UUID dagId) {
        getPipeline(dagId);
        PipelineCompileResult compile = compileService.compile(dagId);
        List<TaskValidation> tasks = compile.tasks().stream()
                .map(t -> new TaskValidation(
                        t.taskKey(), t.taskType(), t.valid(),
                        t.errorMessage(),
                        t.valid() ? null : inferErrorCode(t.errorMessage())))
                .toList();
        List<GraphError> graphErrors = compile.graphErrors().stream()
                .map(msg -> new GraphError("ERROR", "GRAPH_ERROR", msg, List.of()))
                .toList();
        return new PipelineValidationResult(
                dagId, compile.allValidated(), tasks, graphErrors);
    }

    private String inferErrorCode(String message) {
        if (message == null) return null;
        String lower = message.toLowerCase();
        if (lower.contains("c1")) return "C1_VIOLATION";
        if (lower.contains("modelid")) return "MODEL_REQUIRED";
        if (lower.contains("validated")) return "MODEL_NOT_VALIDATED";
        if (lower.contains("synctaskid")) return "SYNC_REF_INCOMPLETE";
        if (lower.contains("cycle")) return "CYCLE_DETECTED";
        return "TASK_INVALID";
    }

    // ---------- 节点运行 ----------

    /** 校验 run 属于当前租户流水线后返回节点运行观测信息。 */
    @Transactional
    public List<TaskRunDTO> listTaskRuns(UUID dagId, UUID runId) {
        getPipeline(dagId);
        // 节点运行列表会暴露日志引用等运行观测字段，必须先确认 run 属于当前 pipeline。
        runRepo.findByIdAndDagIdIn(runId, Set.of(dagId))
                .orElseThrow(() -> new BizException(40400, "运行实例不存在"));
        return taskRunRepo.findByJobRunId(runId).stream()
                .map(TaskRunDTO::of)
                .toList();
    }

    // ---------- ODS→DWD 模板（P3） ----------

    /**
     * 创建一条预置 Spark 标准结构的 ODS→DWD 流水线。
     *
     * <p>模板结构为：SYNC_REF → 可选字段治理 → Spark DWD 落表 → 可选质量门禁。
     */
    @Transactional
    public OdsDwdTemplateResult applyOdsDwdTemplate(OdsDwdTemplateRequest req) {
        if (req.modelId() == null) {
            throw new BizException(40020, "ODS→DWD 模板需要 modelId");
        }
        if (!StringUtils.hasText(req.sourceFqn()) || !StringUtils.hasText(req.targetFqn())) {
            throw new BizException(40021, "ODS→DWD 模板需要 sourceFqn 和 targetFqn");
        }
        // 创建 ODS_DWD 类型流水线。
        Dag dag = new Dag();
        dag.setTenantId(requireTenant());
        dag.setName(StringUtils.hasText(req.pipelineName()) ? req.pipelineName().trim()
                : "ods_dwd_" + req.dbtModelName());
        dag.setDagsterJob("onelake_pipeline_run");
        dag.setDefinition("{}");
        dag.setEnabled(true);
        dag.setVersion(1);
        dag.setPipelineKind("ODS_DWD");
        dag.setStatus("DRAFT");
        dag.setEngine(PIPELINE_ENGINE);
        dag.setResourceGroup(DEFAULT_RESOURCE_GROUP);
        dag.setComputeProfile(DEFAULT_COMPUTE_PROFILE);
        dag = dagRepo.save(dag);

        // 依次创建模板节点和边。
        List<UUID> taskIds = new ArrayList<>();
        List<UUID> edgeIds = new ArrayList<>();
        StringBuilder warnings = new StringBuilder();

        // 1. SYNC_REF：承接 ODS 表就绪事件。
        PipelineTask syncTask = newTask(dag, "sync_ref_ods", TaskType.SYNC_REF,
                "采集: " + req.sourceFqn(), null);
        syncTask.setTargetFqn(req.sourceFqn());
        syncTask = taskRepo.save(syncTask);
        taskIds.add(syncTask.getId());

        PipelineTask previous = syncTask;

        // 2. 可选字段治理占位节点；具体规则后续可在编辑器中调整。
        if (req.includeFieldGovernance()) {
            PipelineTask govTask = newTask(dag, "spark_field_governance", TaskType.SPARK_SQL,
                    "字段治理: " + req.targetFqn(), null);
            govTask.setTargetFqn(intermediateFqn(req.targetFqn(), "governed"));
            govTask.setConfig(sinkDataflowConfig("src", "src.*"));
            govTask = taskRepo.save(govTask);
            taskIds.add(govTask.getId());
            PipelineTaskEdge edgeG = newEdge(dag, previous.getTaskKey(), govTask.getTaskKey(), EdgeLayer.PIPELINE);
            edgeRepo.save(edgeG);
            edgeIds.add(edgeG.getId());
            previous = govTask;
        }

        // 3. Spark DWD 落表节点。
        PipelineTask sinkTask = newTask(dag, "spark_dwd_sink", TaskType.SPARK_SQL,
                "DWD 落表: " + req.targetFqn(), null);
        sinkTask.setTargetFqn(req.targetFqn());
        sinkTask.setConfig(sinkDataflowConfig("src", "src.*"));
        sinkTask = taskRepo.save(sinkTask);
        taskIds.add(sinkTask.getId());
        PipelineTaskEdge sinkEdge = newEdge(dag, previous.getTaskKey(), sinkTask.getTaskKey(), EdgeLayer.PIPELINE);
        edgeRepo.save(sinkEdge);
        edgeIds.add(sinkEdge.getId());
        previous = sinkTask;

        // 4. 可选质量门禁，挂在 Spark DWD 落表之后。
        if (req.includeQualityGate()) {
            PipelineTask gateTask = newTask(dag, "quality_gate", TaskType.QUALITY_GATE,
                    "质量门禁: " + req.targetFqn(), null);
            gateTask.setTargetFqn(req.targetFqn());
            gateTask.setEngine(SPARK_SQL_ENGINE);
            // 预置 4 个默认门禁，保持与前端 QualityGateCards 默认项一致。
            gateTask.setConfig("""
                {"targetModelFqn":"%s","gates":[
                  {"id":"primary","kind":"PRIMARY","title":"主键完整性","enabled":true,"columns":[],"actionOnViolation":"FAIL"},
                  {"id":"accepted_values","kind":"ACCEPTED_VALUES","title":"枚举值命中","enabled":false,"columns":[],"actionOnViolation":"WARN","valuesText":""},
                  {"id":"range","kind":"RANGE","title":"数值范围","enabled":false,"columns":[],"actionOnViolation":"WARN","minValue":"","maxValue":""},
                  {"id":"custom_sql","kind":"CUSTOM_SQL","title":"自定义 SQL","enabled":false,"columns":[],"actionOnViolation":"FAIL","assertionSql":"select * from {{ model }} where 1 = 0"}
                ]}""".formatted(req.targetFqn()));
            gateTask = taskRepo.save(gateTask);
            taskIds.add(gateTask.getId());
            PipelineTaskEdge edgeQ = newEdge(dag, previous.getTaskKey(), gateTask.getTaskKey(), EdgeLayer.PIPELINE);
            edgeRepo.save(edgeQ);
            edgeIds.add(edgeQ.getId());
        }

        if (warnings.length() > 0) {
            log.info("ODS→DWD 模板应用完成但存在提示：{}", warnings);
        }
        return new OdsDwdTemplateResult(dag.getId(), taskIds, edgeIds, warnings.toString());
    }

    private PipelineTask newTask(Dag dag, String taskKey, TaskType type, String name, UUID modelId) {
        PipelineTask t = new PipelineTask();
        t.setTenantId(requireTenant());
        t.setDagId(dag.getId());
        t.setTaskKey(taskKey);
        t.setTaskType(type);
        t.setName(name);
        t.setEngine(defaultTaskEngine(type));
        t.setConfig("{}");
        t.setModelId(modelId);
        return t;
    }

    private PipelineTaskEdge newEdge(Dag dag, String src, String tgt, EdgeLayer layer) {
        PipelineTaskEdge e = new PipelineTaskEdge();
        e.setTenantId(requireTenant());
        e.setDagId(dag.getId());
        e.setSourceKey(src);
        e.setTargetKey(tgt);
        e.setEdgeLayer(layer);
        return e;
    }

    // ---------- 辅助方法 ----------

    private UUID requireTenant() {
        UUID t = TenantContext.getTenantId();
        if (t == null) throw new BizException(40100, "Tenant context required");
        return t;
    }

    /** 已发布流水线继续编辑时只修改 DEV 草稿，并提示存在未发布变更。 */
    private void markUnpublishedChanges(Dag dag) {
        if (dag != null) {
            // 始终执行条件 UPDATE：编辑事务即使读到审批前的 VALIDATED，也会在审批发布提交后
            // 重新按数据库当前状态判断并标记未发布变更。
            dagRepo.markPublishedDagChanged(dag.getId(), dag.getTenantId());
        }
    }

    private Dag getPipelineForUpdate(UUID dagId, UUID tenantId) {
        return dagRepo.findByIdForUpdate(dagId)
                .filter(candidate -> tenantId.equals(candidate.getTenantId()))
                .orElseThrow(() -> new BizException(40400, "Pipeline 不存在"));
    }

    private void lockTenant(UUID tenantId) {
        tenantRepo.findByIdForUpdate(tenantId)
                .orElseThrow(() -> new BizException(40100, "Tenant context required"));
    }

    /** 校验节点请求的必填字段、支持类型和 Spark-only 引擎边界。 */
    private void validateTaskRequest(PipelineTaskRequest req, boolean forCreate) {
        if (forCreate && !StringUtils.hasText(req.taskKey())) {
            throw new BizException(40020, "taskKey 不能为空");
        }
        if (forCreate && !VALID_TASK_TYPES.contains(req.taskType())) {
            throw new BizException(40021, "非法 taskType: " + req.taskType());
        }
        if (!forCreate && req.taskType() != null && !VALID_TASK_TYPES.contains(req.taskType())) {
            throw new BizException(40021, "非法 taskType: " + req.taskType());
        }
    }

    private String serializeConfig(PipelineTaskRequest req) {
        if (req.config() == null) return "{}";
        return JsonUtil.toJson(req.config());
    }

    private String normalizeTaskEngine(String taskType, String requestedEngine) {
        TaskType type = TaskType.valueOf(taskType);
        return switch (type) {
            case PYSPARK -> PYSPARK_ENGINE;
            case TRINO_SQL -> "TRINO";
            case PYTHON, SHELL -> "SCRIPT";
            case BRANCH, CONDITION, SUB_PIPELINE -> "CONTROL";
            case SYNC_REF, SENSOR, WAIT, NOTIFY, ASSERTION -> "OBSERVE";
            case QUALITY_GATE, SPARK_SQL -> SPARK_SQL_ENGINE;
        };
    }

    private String defaultTaskEngine(TaskType type) {
        return normalizeTaskEngine(type.name(), null);
    }

    private String sinkDataflowConfig(String sourceAlias, String select) {
        return """
            {"dataflow":{"nodeKind":"SINK","sourceAlias":"%s","mode":"OVERWRITE","select":"%s"}}
            """.formatted(sourceAlias, select).trim();
    }

    private String intermediateFqn(String targetFqn, String suffix) {
        if (!StringUtils.hasText(targetFqn)) {
            return "onelake.tmp.pipeline_" + suffix;
        }
        String trimmed = targetFqn.trim();
        int dot = trimmed.lastIndexOf('.');
        if (dot < 0 || dot == trimmed.length() - 1) {
            return trimmed + "_" + suffix;
        }
        String prefix = trimmed.substring(0, dot);
        String table = trimmed.substring(dot + 1);
        return prefix + "." + table + "_" + suffix;
    }
}
