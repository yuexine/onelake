package com.onelake.orchestration.service;

import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.util.JsonUtil;
import com.onelake.common.outbox.DomainEvents;
import com.onelake.common.outbox.OutboxPublisher;
import com.onelake.orchestration.domain.entity.Dag;
import com.onelake.orchestration.domain.entity.PipelineTask;
import com.onelake.orchestration.domain.entity.PipelineTaskEdge;
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
import com.onelake.orchestration.repository.TaskRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * CRUD + validation service for pipeline v2 entities.
 *
 * <p>This is the read/write surface used by the Unified Pipeline Editor (P2).
 * It composes {@link PipelineCompileService} for validation (C1 still enforced there).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PipelineService {

    private static final String PIPELINE_ENGINE = "SPARK";
    private static final String SPARK_SQL_ENGINE = "SPARK_SQL";
    private static final String PYSPARK_ENGINE = "PYSPARK";
    private static final String DEFAULT_RESOURCE_GROUP = "spark-default";
    private static final String DEFAULT_COMPUTE_PROFILE = "spark-small";

    private static final Set<String> VALID_TASK_TYPES = Set.of(
            TaskType.QUALITY_GATE.name(), TaskType.SYNC_REF.name(),
            TaskType.SPARK_SQL.name(), TaskType.PYSPARK.name());

    private final DagRepository dagRepo;
    private final PipelineTaskRepository taskRepo;
    private final PipelineTaskEdgeRepository edgeRepo;
    private final TaskRunRepository taskRunRepo;
    private final JobRunRepository runRepo;
    private final PipelineCompileService compileService;
    private final org.springframework.beans.factory.ObjectProvider<OutboxPublisher> outboxPublisher;

    // ---------- pipeline (dag) ----------

    @Transactional
    public Dag getPipeline(UUID dagId) {
        return dagRepo.findByIdAndTenantId(dagId, requireTenant())
                .orElseThrow(() -> new BizException(40400, "Pipeline 不存在"));
    }

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
     * P4-D — Publish status machine: DRAFT → VALIDATED → PUBLISHED.
     *
     * <p>Transitions enforced:
     * <ul>
     *   <li>DRAFT → VALIDATED: requires successful {@link #validate(UUID)} (all tasks valid).</li>
     *   <li>VALIDATED → PUBLISHED: emits {@code pipeline.published} Outbox event.</li>
     *   <li>Any → DRAFT: allowed (revert).</li>
     *   <li>Other transitions rejected.</li>
     * </ul>
     */
    @Transactional
    public Dag updatePipelineStatus(UUID dagId, String targetStatusRaw) {
        Dag dag = getPipeline(dagId);
        String current = dag.getStatus() == null ? "DRAFT" : dag.getStatus().toUpperCase();
        String target = targetStatusRaw == null ? "" : targetStatusRaw.toUpperCase();
        if (!Set.of("DRAFT", "VALIDATED", "PUBLISHED").contains(target)) {
            throw new BizException(40020, "非法 pipeline status: " + targetStatusRaw);
        }
        if (target.equals(current)) {
            return dag; // no-op
        }

        // Enforce transition rules
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

        dag.setStatus(target);
        dag.setVersion((dag.getVersion() == null ? 0 : dag.getVersion()) + 1);
        dag = dagRepo.save(dag);

        // Emit pipeline.published when entering PUBLISHED state
        if ("PUBLISHED".equals(target)) {
            emitPipelinePublishedEvent(dag);
        }
        log.info("Pipeline {} status transition: {} → {} (v{})",
                dag.getId(), current, target, dag.getVersion());
        return dag;
    }

    private void emitPipelinePublishedEvent(Dag dag) {
        OutboxPublisher publisher = outboxPublisher.getIfAvailable();
        if (publisher == null) {
            log.warn("OutboxPublisher not available — skipping pipeline.published event for {}",
                    dag.getId());
            return;
        }
        java.util.Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("pipelineId", dag.getId().toString());
        payload.put("tenantId", dag.getTenantId() == null ? null : dag.getTenantId().toString());
        payload.put("version", dag.getVersion());
        payload.put("pipelineKind", dag.getPipelineKind() == null ? "BLANK" : dag.getPipelineKind());
        payload.put("publishedBy", com.onelake.common.context.TenantContext.getUserId() == null
                ? null : com.onelake.common.context.TenantContext.getUserId().toString());
        payload.put("publishedAt", java.time.Instant.now().toString());        // targetFqns: distinct non-null across tasks
        List<String> targets = taskRepo.findByDagIdOrderByCreatedAtAsc(dag.getId()).stream()
                .map(PipelineTask::getTargetFqn)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        payload.put("targetFqns", targets);
        payload.put("taskCount", taskRepo.findByDagIdOrderByCreatedAtAsc(dag.getId()).size());
        publisher.publish(DomainEvents.PIPELINE_PUBLISHED, dag.getId().toString(), payload);
    }

    // ---------- tasks ----------

    @Transactional
    public List<PipelineTaskDTO> listTasks(UUID dagId) {
        getPipeline(dagId); // tenant scoping
        return taskRepo.findByDagIdOrderByCreatedAtAsc(dagId).stream()
                .map(PipelineTaskDTO::of)
                .toList();
    }

    @Transactional
    public PipelineTaskDTO createTask(UUID dagId, PipelineTaskRequest req) {
        Dag dag = getPipeline(dagId);
        validateTaskRequest(req, true);
        if (taskRepo.findByDagIdAndTaskKey(dagId, req.taskKey()).isPresent()) {
            throw new BizException(40901, "task_key 已存在: " + req.taskKey());
        }
        PipelineTask t = new PipelineTask();
        t.setTenantId(requireTenant());
        t.setDagId(dagId);
        t.setTaskKey(req.taskKey());
        t.setTaskType(TaskType.valueOf(req.taskType()));
        t.setName(req.name() == null ? req.taskKey() : req.name());
        t.setEngine(normalizeTaskEngine(req.taskType(), req.engine()));
        t.setTargetFqn(req.targetFqn());
        t.setModelId(req.modelId());
        t.setSyncTaskId(req.syncTaskId());
        t.setConfig(serializeConfig(req));
        t.setPositionX(req.positionX());
        t.setPositionY(req.positionY());
        t = taskRepo.save(t);
        log.info("Pipeline {} task created: key={} type={}", dagId, t.getTaskKey(), t.getTaskType());
        return PipelineTaskDTO.of(t);
    }

    @Transactional
    public PipelineTaskDTO updateTask(UUID dagId, String taskKey, PipelineTaskRequest req) {
        getPipeline(dagId);
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
        // Reset compile state on edit
        t.setCompileStatus(com.onelake.orchestration.domain.enums.TaskCompileStatus.DRAFT);
        t.setCompileError(null);
        t.setExecutable(false);
        t.setUpdatedAt(Instant.now());
        t = taskRepo.save(t);
        return PipelineTaskDTO.of(t);
    }

    @Transactional
    public void deleteTask(UUID dagId, String taskKey) {
        getPipeline(dagId);
        PipelineTask t = taskRepo.findByDagIdAndTaskKey(dagId, taskKey)
                .orElseThrow(() -> new BizException(40400, "task 不存在: " + taskKey));
        taskRepo.delete(t);
        // Cascade-delete edges referencing this task_key
        edgeRepo.findByDagId(dagId).stream()
                .filter(e -> e.getSourceKey().equals(taskKey) || e.getTargetKey().equals(taskKey))
                .forEach(edgeRepo::delete);
    }

    // ---------- edges ----------

    @Transactional
    public List<PipelineTaskEdgeDTO> listEdges(UUID dagId) {
        getPipeline(dagId);
        return edgeRepo.findByDagId(dagId).stream()
                .map(PipelineTaskEdgeDTO::of)
                .toList();
    }

    @Transactional
    public PipelineTaskEdgeDTO createEdge(UUID dagId, PipelineTaskEdgeRequest req) {
        getPipeline(dagId);
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
        return PipelineTaskEdgeDTO.of(e);
    }

    private String defaultFreshnessPolicy(String targetInput) {
        if ("left".equalsIgnoreCase(targetInput) || "right".equalsIgnoreCase(targetInput)) {
            return "SAME_FRESHNESS_WINDOW";
        }
        return "LATEST";
    }

    @Transactional
    public void deleteEdge(UUID dagId, String sourceKey, String targetKey) {
        getPipeline(dagId);
        edgeRepo.findByDagId(dagId).stream()
                .filter(e -> e.getSourceKey().equals(sourceKey) && e.getTargetKey().equals(targetKey))
                .findFirst()
                .ifPresent(edgeRepo::delete);
    }

    // ---------- validation ----------

    /**
     * L1+L2 validation (§6.7). Runs the compile service (which enforces C1) and
     * translates the result into the API response shape.
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

    // ---------- task runs ----------

    @Transactional
    public List<TaskRunDTO> listTaskRuns(UUID dagId, UUID runId) {
        getPipeline(dagId);
        // task_run 会暴露日志引用等运行观测字段，必须先确认 run 属于当前 pipeline。
        runRepo.findByIdAndDagIdIn(runId, Set.of(dagId))
                .orElseThrow(() -> new BizException(40400, "运行实例不存在"));
        return taskRunRepo.findByJobRunId(runId).stream()
                .map(TaskRunDTO::of)
                .toList();
    }

    // ---------- ODS→DWD template (P3) ----------

    /**
     * Create a BLANK pipeline prepopulated with the canonical Spark ODS→DWD task structure:
     * SYNC_REF → optional Spark field governance → Spark DWD sink → QUALITY_GATE.
     */
    @Transactional
    public OdsDwdTemplateResult applyOdsDwdTemplate(OdsDwdTemplateRequest req) {
        if (req.modelId() == null) {
            throw new BizException(40020, "ODS→DWD 模板需要 modelId");
        }
        if (!StringUtils.hasText(req.sourceFqn()) || !StringUtils.hasText(req.targetFqn())) {
            throw new BizException(40021, "ODS→DWD 模板需要 sourceFqn 和 targetFqn");
        }
        // Create pipeline (dag) with kind=ODS_DWD
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

        // Create tasks
        List<UUID> taskIds = new ArrayList<>();
        List<UUID> edgeIds = new ArrayList<>();
        StringBuilder warnings = new StringBuilder();

        // 1. SYNC_REF
        PipelineTask syncTask = newTask(dag, "sync_ref_ods", TaskType.SYNC_REF,
                "采集: " + req.sourceFqn(), null);
        syncTask.setTargetFqn(req.sourceFqn());
        syncTask = taskRepo.save(syncTask);
        taskIds.add(syncTask.getId());

        PipelineTask previous = syncTask;

        // 2. Optional Spark field-governance placeholder. Rules can be refined in the editor.
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

        // 3. Spark DWD sink.
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

        // 4. QUALITY_GATE (optional, after Spark DWD sink)
        if (req.includeQualityGate()) {
            PipelineTask gateTask = newTask(dag, "quality_gate", TaskType.QUALITY_GATE,
                    "质量门禁: " + req.targetFqn(), null);
            gateTask.setTargetFqn(req.targetFqn());
            gateTask.setEngine(SPARK_SQL_ENGINE);
            // Seed config with default 4 gates (matches QualityGateCards defaults)
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
            log.info("OdsDwdTemplate applied with warnings: {}", warnings);
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

    // ---------- helpers ----------

    private UUID requireTenant() {
        UUID t = TenantContext.getTenantId();
        if (t == null) throw new BizException(40100, "Tenant context required");
        return t;
    }

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
        if (TaskType.PYSPARK.name().equals(taskType)) {
            return PYSPARK_ENGINE;
        }
        return SPARK_SQL_ENGINE;
    }

    private String defaultTaskEngine(TaskType type) {
        return type == TaskType.PYSPARK ? PYSPARK_ENGINE : SPARK_SQL_ENGINE;
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
