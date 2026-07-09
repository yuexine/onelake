package com.onelake.orchestration.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.outbox.OutboxPublisher;
import com.onelake.common.outbox.DomainEvents;
import com.onelake.common.util.JsonUtil;
import com.onelake.orchestration.client.DagsterClient;
import com.onelake.orchestration.domain.entity.Dag;
import com.onelake.orchestration.domain.entity.JobRun;
import com.onelake.orchestration.domain.entity.PipelineTask;
import com.onelake.orchestration.domain.entity.PipelineTaskEdge;
import com.onelake.orchestration.domain.entity.TaskRun;
import com.onelake.orchestration.domain.enums.DagStatus;
import com.onelake.orchestration.domain.enums.EdgeLayer;
import com.onelake.orchestration.domain.enums.TaskRunStatus;
import com.onelake.orchestration.domain.enums.TaskType;
import com.onelake.orchestration.domain.enums.TriggerType;
import com.onelake.orchestration.dto.DagDTO;
import com.onelake.orchestration.dto.JobRunDTO;
import com.onelake.orchestration.dto.PipelineCompileResult;
import com.onelake.orchestration.dto.TaskRunCallbackRequest;
import com.onelake.orchestration.dto.TaskRunCallbackResult;
import com.onelake.orchestration.dto.TaskRerunResult;
import com.onelake.orchestration.repository.DagRepository;
import com.onelake.orchestration.repository.JobRunRepository;
import com.onelake.orchestration.repository.PipelineTaskEdgeRepository;
import com.onelake.orchestration.repository.PipelineTaskRepository;
import com.onelake.orchestration.repository.TaskRunRepository;
import com.onelake.orchestration.service.spi.DagsterRunConfig;
import com.onelake.orchestration.service.spi.EngineType;
import com.onelake.orchestration.service.spi.SparkRunConfigBuilder;
import com.onelake.orchestration.service.spi.TaskBundleContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrchestrationService {

    private static final String DRAFT_DAGSTER_JOB = "sql_workbench_draft";
    private static final String PIPELINE_JOB_NAME = "onelake_pipeline_run";
    private static final String PIPELINE_GRAPH_JOB_NAME = "onelake_pipeline_graph_run";
    private static final int MAX_LOG_TAIL_LINES = 10_000;

    private final DagRepository dagRepo;
    private final JobRunRepository runRepo;
    private final DagsterClient dagster;
    private final JdbcTemplate jdbc;
    private final RuntimeContractService runtimeContractService;

    // Pipeline v2 dependencies (P1+)
    private final PipelineCompileService pipelineCompileService;
    private final PipelineTaskRepository pipelineTaskRepo;
    private final PipelineTaskEdgeRepository pipelineTaskEdgeRepo;
    private final TaskRunRepository taskRunRepo;
    private final SparkRunConfigBuilder sparkBuilder;
    private final ObjectProvider<OutboxPublisher> outboxPublisher;
    private final PipelineLogStorage pipelineLogStorage;

    @Value("${onelake.orchestration.pipeline-execution-mode:LEGACY}")
    private String pipelineExecutionMode = "LEGACY";

    @Value("${onelake.orchestration.callback-base-url:}")
    private String pipelineCallbackBaseUrl = "";

    @Value("${onelake.orchestration.max-parallel:4}")
    private int pipelineMaxParallel = 4;

    @Transactional
    public DagDTO createDag(String name, String dagsterJob, Map<String, Object> definition,
                            String scheduleCron) {
        return createDag(name, dagsterJob, definition, scheduleCron, true);
    }

    @Transactional
    public DagDTO createDag(String name, String dagsterJob, Map<String, Object> definition,
                            String scheduleCron, Boolean enabled) {
        validateDag(name, definition);
        Dag dag = new Dag();
        dag.setTenantId(TenantContext.getTenantId());
        dag.setName(name.trim());
        dag.setDagsterJob(dagsterJob == null || dagsterJob.isBlank() ? "sql_workbench_draft" : dagsterJob);
        dag.setDefinition(JsonUtil.toJson(definition));
        dag.setScheduleCron(scheduleCron);
        dag.setEnabled(enabled == null ? true : enabled);
        dag.setVersion(1);
        dagRepo.save(dag);
        return toDTO(dag);
    }

    @Transactional
    public DagDTO updateDag(UUID id, String name, String dagsterJob, Map<String, Object> definition,
                            String scheduleCron, Boolean enabled) {
        validateDag(name, definition);
        Dag dag = findTenantDag(id);
        dag.setName(name.trim());
        if (dagsterJob != null && !dagsterJob.isBlank()) {
            dag.setDagsterJob(dagsterJob);
        }
        dag.setDefinition(JsonUtil.toJson(definition));
        dag.setScheduleCron(scheduleCron);
        if (enabled != null) {
            dag.setEnabled(enabled);
        }
        dag.setVersion(dag.getVersion() == null ? 1 : dag.getVersion() + 1);
        dagRepo.save(dag);
        return toDTO(dag);
    }

    @Transactional
    public DagDTO getDag(UUID id) {
        return toDTOWithLastRun(findTenantDag(id));
    }

    @Transactional
    public List<DagDTO> listDags() {
        return dagRepo.findByTenantId(TenantContext.getTenantId()).stream()
            .map(this::toDTOWithLastRun)
            .toList();
    }

    @Transactional(noRollbackFor = BizException.class)
    public UUID triggerDag(UUID dagId, TriggerType trigger) {
        Dag dag = findTenantDag(dagId);
        TriggerReadiness readiness = triggerReadiness(dag);
        if (!readiness.triggerable()) {
            throw new BizException(40012, readiness.reason());
        }
        JobRun run = new JobRun();
        run.setDagId(dagId);
        run.setTriggerType(trigger);
        run.setStatus(DagStatus.QUEUED);
        run.setStartedAt(Instant.now());
        run.setTriggeredBy(TenantContext.getUserId());
        run.setTriggeredByName(currentTriggerActorName());
        runRepo.save(run);

        try {
            String dagsterRunId = dagster.launch(dag.getDagsterJob(), "onelake", "onelake-loc");
            run.setDagsterRunId(dagsterRunId);
            run.setStatus(DagStatus.RUNNING);
            runRepo.save(run);
        } catch (RuntimeException ex) {
            run.setStatus(DagStatus.FAILED);
            run.setFinishedAt(Instant.now());
            runRepo.save(run);
            if (ex instanceof BizException bizException) {
                throw bizException;
            }
            throw new BizException(50200, "Dagster 触发失败: " + ex.getMessage());
        }
        return run.getId();
    }

    /**
     * P1 pipeline v2 trigger path.
     *
     * <p><b>C2 (docs/流水线模块重设计方案.md §7 P1)</b>: this is a NEW code path that
     * coexists with the generic {@link #triggerDag(UUID, TriggerType)} for non-pipeline
     * Dagster jobs. External model execution has been removed. The new path:
     * <ol>
     *   <li>Compiles the pipeline via {@link PipelineCompileService}.</li>
     *   <li>Builds Spark Dagster runConfig via {@link SparkRunConfigBuilder}.</li>
     *   <li>Launches {@code onelake_pipeline_run} Dagster job.</li>
     *   <li>Creates one {@link TaskRun} per observable task and initializes status by topology.</li>
     *   <li><b>Does NOT write modeling.* schema</b> — modeling updates come from Outbox events.</li>
     * </ol>
     *
     * @return the JobRun ID
     */
    @Transactional(noRollbackFor = BizException.class)
    public UUID triggerPipelineRun(UUID dagId, TriggerType trigger) {
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new BizException(40100, "Tenant context required to trigger pipeline");
        }
        Dag dag = findTenantDag(dagId);
        String activeJobName = activePipelineDagsterJob();
        validatePipelineRuntimeContract(dag, activeJobName);

        // 1. Compile
        PipelineCompileResult plan = pipelineCompileService.compile(dagId);
        if (!plan.allValidated()) {
            throw new BizException(40060, "Pipeline 编译未通过，无法触发: "
                    + String.join("; ",
                        plan.tasks().stream()
                                .filter(t -> !t.valid())
                                .map(t -> t.taskKey() + ": " + t.errorMessage())
                                .toList())
                    + (plan.graphErrors().isEmpty() ? "" : "; " + String.join("; ", plan.graphErrors())));
        }
        // 2. Readiness: ensure at least one real engine task; observation-only
        // nodes (SYNC_REF/QUALITY_GATE) still get task_run rows for UI visibility.
        List<PipelineTask> tasks = pipelineTaskRepo.findByDagIdOrderByCreatedAtAsc(dagId);
        long executableCount = tasks.stream().filter(PipelineTask::getExecutable).count();
        if (executableCount == 0) {
            throw new BizException(40061, "流水线没有可执行任务（可能是 Spark 任务尚未就绪，P-Spark 阶段后启用）");
        }

        // 3. Create JobRun
        JobRun run = new JobRun();
        run.setDagId(dagId);
        run.setTriggerType(trigger);
        run.setStatus(DagStatus.QUEUED);
        run.setStartedAt(Instant.now());
        run.setTriggeredBy(TenantContext.getUserId());
        run.setTriggeredByName(currentTriggerActorName());
        runRepo.save(run);

        // 4. Create TaskRun per valid task. Status is initialized from the data-flow
        // DAG so the run instance reflects direct-upstream readiness instead of
        // flattening every node to QUEUED.
        List<PipelineTask> observable = observableTasks(plan, tasks);
        List<PipelineTaskEdge> pipelineEdges = pipelineTaskEdgeRepo.findByDagId(dagId);
        Map<String, TaskRunStatus> initialStatuses = initialTaskRunStatuses(observable, pipelineEdges);
        for (PipelineTask t : observable) {
            TaskRun tr = new TaskRun();
            tr.setTenantId(tenantId);
            tr.setJobRunId(run.getId());
            tr.setTaskKey(t.getTaskKey());
            TaskRunStatus initialStatus = initialStatuses.getOrDefault(t.getTaskKey(), TaskRunStatus.QUEUED);
            tr.setStatus(initialStatus);
            if (initialStatus == TaskRunStatus.RUNNING || initialStatus == TaskRunStatus.SUCCEEDED) {
                tr.setStartedAt(run.getStartedAt());
            }
            if (initialStatus == TaskRunStatus.SUCCEEDED) {
                tr.setFinishedAt(run.getStartedAt());
                if (StringUtils.hasText(t.getTargetFqn())) {
                    tr.setArtifactPath("table:" + normalizeTableFqn(t.getTargetFqn()));
                }
            }
            taskRunRepo.save(tr);
        }

        // 5. Build Dagster runConfig (C3 — Java builds, Dagster executes)
        TaskBundleContext ctx = new TaskBundleContext(
                dagId, tenantId, run.getId(), plan,
                plan.pipelineTag(),
                dag.getResourceGroup() == null ? "spark-default" : dag.getResourceGroup(),
                dag.getComputeProfile() == null ? "spark-small" : dag.getComputeProfile()
        );
        DagsterRunConfig runConfig = buildPipelineRunConfig(ctx, tasks, pipelineEdges);

        // 6. Launch Dagster
        try {
            List<Map<String, String>> tags = List.of(
                    Map.of("key", "onelake.pipeline_id", "value", dagId.toString()),
                    Map.of("key", "onelake.run_id", "value", run.getId().toString()),
                    Map.of("key", "onelake.tenant_id", "value", tenantId.toString()),
                    Map.of("key", "onelake.trigger_type", "value", trigger.name()),
                    Map.of("key", "onelake.engine", "value", pipelineEngineTag(tasks))
            );
            String dagsterRunId = dagster.launch(
                    runConfig.jobName(), "onelake", "onelake-loc",
                    runConfig.opConfig(), tags);
            run.setDagsterRunId(dagsterRunId);
            run.setStatus(DagStatus.RUNNING);
            runRepo.save(run);
            log.info("Spark pipeline {} launched, runId={}, dagsterRunId={}",
                    dagId, run.getId(), dagsterRunId);
        } catch (RuntimeException ex) {
            run.setStatus(DagStatus.FAILED);
            run.setFinishedAt(Instant.now());
            runRepo.save(run);
            // Fail all non-terminal task_runs
            taskRunRepo.findByJobRunId(run.getId()).forEach(tr -> {
                if (!isTerminalTaskRunStatus(tr.getStatus())) {
                    tr.setStatus(TaskRunStatus.FAILED);
                    tr.setErrorMsg("Dagster launch failed: " + truncate(ex.getMessage(), 3900));
                    tr.setFinishedAt(Instant.now());
                    taskRunRepo.save(tr);
                }
            });
            publishPipelineRunEvent(dag, run, plan, false, ex.getMessage());
            if (ex instanceof BizException bizException) throw bizException;
            throw new BizException(50200, "Dagster 触发失败: " + ex.getMessage());
        }
        return run.getId();
    }

    @Transactional(noRollbackFor = BizException.class)
    public TaskRerunResult rerunTask(UUID dagId, UUID runId, String taskKey, String modeRaw) {
        if (!isGraphPipelineExecutionMode()) {
            throw new BizException(40062, "从失败续跑仅在 GRAPH 执行模式可用");
        }
        if (!StringUtils.hasText(taskKey)) {
            throw new BizException(40021, "taskKey 不能为空");
        }
        RerunMode mode = RerunMode.parse(modeRaw);
        Dag dag = findTenantDag(dagId);
        validatePipelineRuntimeContract(dag, PIPELINE_GRAPH_JOB_NAME);
        JobRun run = runRepo.findByIdAndDagIdIn(runId, Set.of(dag.getId()))
                .orElseThrow(() -> new BizException(40400, "运行实例不存在"));

        // 重跑会重置一批节点状态，必须与节点回调和 GRAPH 终态补偿串行化，避免旧回调覆盖新状态。
        List<TaskRun> lockedTaskRuns = new ArrayList<>(taskRunRepo.findByJobRunIdForUpdate(runId));
        Map<String, TaskRun> runByTaskKey = lockedTaskRuns.stream()
                .collect(Collectors.toMap(TaskRun::getTaskKey, Function.identity(), (a, b) -> a,
                        LinkedHashMap::new));
        TaskRun target = runByTaskKey.get(taskKey);
        if (target == null || (dag.getTenantId() != null && target.getTenantId() != null
                && !dag.getTenantId().equals(target.getTenantId()))) {
            throw new BizException(40400, "task_run 不存在: " + taskKey);
        }
        List<PipelineTaskEdge> pipelineEdges = pipelineTaskEdgeRepo.findByDagId(dagId);
        if (pipelineEdges == null) {
            pipelineEdges = List.of();
        }
        // SINGLE 只重跑目标节点；DOWNSTREAM 沿 PIPELINE 边续跑，但跳过已经成功的下游节点。
        Set<String> subgraph = resolveRerunSubgraph(taskKey, mode, runByTaskKey, pipelineEdges);
        boolean canRerunTarget = isRerunnableTaskRunStatus(target.getStatus())
                || canResumeFromSucceededRoot(taskKey, mode, target, subgraph, runByTaskKey);
        if (!canRerunTarget) {
            throw new BizException(40063, "仅可对失败节点重跑，当前状态: " + target.getStatus());
        }
        if (mode == RerunMode.DOWNSTREAM) {
            firstNonSucceededExternalUpstream(taskKey, subgraph, runByTaskKey, pipelineEdges)
                    .ifPresent(dependency -> {
                        throw new BizException(40063, "从失败续跑存在未成功的外部上游: " + dependency);
                    });
        }

        PipelineCompileResult plan = pipelineCompileService.compile(dagId);
        if (!plan.allValidated()) {
            throw new BizException(40060, "Pipeline 编译未通过，无法重跑: "
                    + String.join("; ",
                    plan.tasks().stream()
                            .filter(t -> !t.valid())
                            .map(t -> t.taskKey() + ": " + t.errorMessage())
                            .toList())
                    + (plan.graphErrors().isEmpty() ? "" : "; " + String.join("; ", plan.graphErrors())));
        }
        List<PipelineTask> allTasks = pipelineTaskRepo.findByDagIdOrderByCreatedAtAsc(dagId);
        List<PipelineTask> observable = observableTasks(plan, allTasks);
        Map<String, PipelineTask> observableByKey = observable.stream()
                .collect(Collectors.toMap(PipelineTask::getTaskKey, Function.identity(), (a, b) -> a,
                        LinkedHashMap::new));
        List<String> missingTaskDefinitions = subgraph.stream()
                .filter(key -> !observableByKey.containsKey(key))
                .toList();
        if (!missingTaskDefinitions.isEmpty()) {
            throw new BizException(40060, "Pipeline 当前定义缺少可重跑节点: "
                    + String.join(", ", missingTaskDefinitions));
        }
        List<PipelineTask> subgraphTasks = observable.stream()
                .filter(task -> subgraph.contains(task.getTaskKey()))
                .toList();

        Instant now = Instant.now();
        Map<String, Integer> baseAttempts = new LinkedHashMap<>();
        for (String key : subgraph) {
            TaskRun tr = runByTaskKey.get(key);
            // task_run.attempt 保存跨 Dagster run 的累计次数，传给 Dagster 后作为本次本地重试的起点。
            int nextAttempt = Math.max(1, tr.getAttempt() + 1);
            tr.setAttempt(nextAttempt);
            baseAttempts.put(key, nextAttempt);
            tr.setStatus(key.equals(taskKey) ? TaskRunStatus.RUNNING : TaskRunStatus.QUEUED);
            tr.setStartedAt(key.equals(taskKey) ? now : null);
            tr.setFinishedAt(null);
            tr.setErrorMsg(null);
            tr.setUpdatedAt(now);
            taskRunRepo.save(tr);
        }

        // M1 复用同一个 JobRun：先清掉旧 Dagster runId，launch 成功后再写入本次新值。
        run.setStatus(DagStatus.RUNNING);
        run.setDagsterRunId(null);
        run.setFinishedAt(null);
        run.setUpdatedAt(now);
        runRepo.save(run);

        TaskBundleContext ctx = new TaskBundleContext(
                dagId, dag.getTenantId(), run.getId(), plan,
                plan.pipelineTag(),
                dag.getResourceGroup() == null ? "spark-default" : dag.getResourceGroup(),
                dag.getComputeProfile() == null ? "spark-small" : dag.getComputeProfile()
        );
        DagsterRunConfig runConfig = sparkBuilder.buildGraphRunConfig(
                ctx,
                subgraphTasks,
                pipelineEdges,
                pipelineCallbackBaseUrl == null ? "" : pipelineCallbackBaseUrl.trim(),
                Math.max(1, pipelineMaxParallel),
                baseAttempts);

        try {
            String dagsterRunId = dagster.launch(
                    runConfig.jobName(), "onelake", "onelake-loc",
                    runConfig.opConfig(), pipelineRerunTags(dag, run, taskKey, mode, subgraphTasks));
            run.setDagsterRunId(dagsterRunId);
            run.setStatus(DagStatus.RUNNING);
            run.setUpdatedAt(Instant.now());
            runRepo.save(run);
            log.info("Spark pipeline {} rerun launched, runId={}, taskKey={}, mode={}, dagsterRunId={}",
                    dagId, run.getId(), taskKey, mode, dagsterRunId);
            return new TaskRerunResult(runId, new ArrayList<>(subgraph), dagsterRunId);
        } catch (RuntimeException ex) {
            // launch 失败发生在节点已重置之后，需要像首触发失败一样把子图收口到可观测 FAILED。
            Instant failedAt = Instant.now();
            run.setStatus(DagStatus.FAILED);
            run.setFinishedAt(failedAt);
            run.setUpdatedAt(failedAt);
            runRepo.save(run);
            for (String key : subgraph) {
                TaskRun tr = runByTaskKey.get(key);
                if (!isTerminalTaskRunStatus(tr.getStatus())) {
                    tr.setStatus(TaskRunStatus.FAILED);
                    tr.setErrorMsg("Dagster rerun launch failed: " + truncate(ex.getMessage(), 3900));
                    tr.setFinishedAt(failedAt);
                    tr.setUpdatedAt(failedAt);
                    taskRunRepo.save(tr);
                }
            }
            publishPipelineRunEvent(dag, run, plan, false, ex.getMessage());
            if (ex instanceof BizException bizException) {
                throw bizException;
            }
            throw new BizException(50200, "Dagster 重跑触发失败: " + ex.getMessage());
        }
    }

    private List<PipelineTask> observableTasks(PipelineCompileResult plan, List<PipelineTask> tasks) {
        Map<String, PipelineTask> byKey = tasks.stream()
                .collect(Collectors.toMap(PipelineTask::getTaskKey, Function.identity(), (a, b) -> a,
                        LinkedHashMap::new));
        return plan.tasks().stream()
                .filter(PipelineCompileResult.TaskCompileResult::valid)
                .map(PipelineCompileResult.TaskCompileResult::taskKey)
                .map(byKey::get)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private Map<String, TaskRunStatus> initialTaskRunStatuses(List<PipelineTask> tasks,
                                                              List<PipelineTaskEdge> allEdges) {
        Map<String, PipelineTask> taskByKey = tasks.stream()
                .collect(Collectors.toMap(PipelineTask::getTaskKey, Function.identity(), (a, b) -> a,
                        LinkedHashMap::new));
        Map<String, List<String>> upstreamsByTarget = new LinkedHashMap<>();
        List<PipelineTaskEdge> edges = allEdges == null ? List.of() : allEdges;
        for (PipelineTaskEdge edge : edges) {
            if (edge.getEdgeLayer() != EdgeLayer.PIPELINE) {
                continue;
            }
            if (!taskByKey.containsKey(edge.getSourceKey()) || !taskByKey.containsKey(edge.getTargetKey())) {
                continue;
            }
            upstreamsByTarget
                    .computeIfAbsent(edge.getTargetKey(), ignored -> new ArrayList<>())
                    .add(edge.getSourceKey());
        }

        Map<String, TaskRunStatus> statuses = new LinkedHashMap<>();
        for (PipelineTask task : tasks) {
            if (task.getTaskType() == TaskType.SYNC_REF) {
                statuses.put(task.getTaskKey(), TaskRunStatus.SUCCEEDED);
            } else {
                statuses.put(task.getTaskKey(), TaskRunStatus.QUEUED);
            }
        }

        boolean changed = true;
        while (changed) {
            changed = false;
            for (PipelineTask task : tasks) {
                String taskKey = task.getTaskKey();
                if (statuses.get(taskKey) != TaskRunStatus.QUEUED) {
                    continue;
                }
                List<String> upstreams = upstreamsByTarget.getOrDefault(taskKey, List.of());
                boolean ready = upstreams.isEmpty()
                        || upstreams.stream().allMatch(sourceKey -> statuses.get(sourceKey) == TaskRunStatus.SUCCEEDED);
                if (ready) {
                    statuses.put(taskKey, TaskRunStatus.RUNNING);
                    changed = true;
                }
            }
        }
        return statuses;
    }

    private Set<String> resolveRerunSubgraph(String rootKey,
                                             RerunMode mode,
                                             Map<String, TaskRun> runByTaskKey,
                                             List<PipelineTaskEdge> allEdges) {
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        selected.add(rootKey);
        if (mode == RerunMode.SINGLE) {
            return selected;
        }

        Map<String, List<String>> downstreamBySource = new LinkedHashMap<>();
        List<PipelineTaskEdge> edges = allEdges == null ? List.of() : allEdges;
        for (PipelineTaskEdge edge : edges) {
            if (edge.getEdgeLayer() != EdgeLayer.PIPELINE) {
                continue;
            }
            downstreamBySource
                    .computeIfAbsent(edge.getSourceKey(), ignored -> new ArrayList<>())
                    .add(edge.getTargetKey());
        }

        Deque<String> queue = new ArrayDeque<>();
        queue.add(rootKey);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            for (String childKey : downstreamBySource.getOrDefault(current, List.of())) {
                TaskRun childRun = runByTaskKey.get(childKey);
                // 已成功节点代表本次续跑不需要重新物化；同时也不再穿透它继续扩展子图。
                if (childRun == null || childRun.getStatus() == TaskRunStatus.SUCCEEDED) {
                    continue;
                }
                if (selected.add(childKey)) {
                    queue.addLast(childKey);
                }
            }
        }
        return selected;
    }

    private boolean canResumeFromSucceededRoot(String rootKey,
                                               RerunMode mode,
                                               TaskRun rootRun,
                                               Set<String> subgraph,
                                               Map<String, TaskRun> runByTaskKey) {
        if (mode != RerunMode.DOWNSTREAM
                || rootRun == null
                || rootRun.getStatus() != TaskRunStatus.SUCCEEDED
                || subgraph == null
                || subgraph.size() <= 1) {
            return false;
        }

        boolean hasBlockedDescendant = false;
        for (String key : subgraph) {
            if (key.equals(rootKey)) {
                continue;
            }
            TaskRun run = runByTaskKey.get(key);
            if (run != null && isRerunnableTaskRunStatus(run.getStatus())) {
                hasBlockedDescendant = true;
            }
        }
        return hasBlockedDescendant;
    }

    private Optional<String> firstNonSucceededExternalUpstream(String rootKey,
                                                              Set<String> subgraph,
                                                              Map<String, TaskRun> runByTaskKey,
                                                              List<PipelineTaskEdge> allEdges) {
        if (subgraph == null || subgraph.size() <= 1) {
            return Optional.empty();
        }

        Map<String, List<String>> upstreamByTarget = new LinkedHashMap<>();
        for (PipelineTaskEdge edge : allEdges == null ? List.<PipelineTaskEdge>of() : allEdges) {
            if (edge.getEdgeLayer() != EdgeLayer.PIPELINE) {
                continue;
            }
            upstreamByTarget
                    .computeIfAbsent(edge.getTargetKey(), ignored -> new ArrayList<>())
                    .add(edge.getSourceKey());
        }

        for (String key : subgraph) {
            if (key.equals(rootKey)) {
                continue;
            }
            for (String upstreamKey : upstreamByTarget.getOrDefault(key, List.of())) {
                if (subgraph.contains(upstreamKey)) {
                    continue;
                }
                TaskRun upstreamRun = runByTaskKey.get(upstreamKey);
                // 子图会过滤外部边，只有外部入边已经成功时才允许本次续跑继续调度下游。
                if (upstreamRun == null || upstreamRun.getStatus() != TaskRunStatus.SUCCEEDED) {
                    return Optional.of(upstreamKey + " -> " + key);
                }
            }
        }
        return Optional.empty();
    }

    private DagsterRunConfig buildPipelineRunConfig(TaskBundleContext ctx,
                                                    List<PipelineTask> tasks,
                                                    List<PipelineTaskEdge> pipelineEdges) {
        // GRAPH/LEGACY 只影响 Dagster 作业与 runConfig 形状，C3 仍保持 Java 生成、Dagster 执行。
        if (isGraphPipelineExecutionMode()) {
            return sparkBuilder.buildGraphRunConfig(
                    ctx,
                    tasks,
                    pipelineEdges,
                    pipelineCallbackBaseUrl == null ? "" : pipelineCallbackBaseUrl.trim(),
                    Math.max(1, pipelineMaxParallel),
                    Map.of());
        }
        return sparkBuilder.build(
                ctx,
                tasks,
                pipelineCallbackBaseUrl == null ? "" : pipelineCallbackBaseUrl.trim());
    }

    private List<Map<String, String>> pipelineRerunTags(Dag dag,
                                                        JobRun run,
                                                        String taskKey,
                                                        RerunMode mode,
                                                        List<PipelineTask> tasks) {
        return List.of(
                Map.of("key", "onelake.pipeline_id", "value", dag.getId().toString()),
                Map.of("key", "onelake.run_id", "value", run.getId().toString()),
                Map.of("key", "onelake.tenant_id", "value", asString(dag.getTenantId())),
                Map.of("key", "onelake.trigger_type", "value", asString(run.getTriggerType())),
                Map.of("key", "onelake.engine", "value", pipelineEngineTag(tasks)),
                Map.of("key", "onelake.rerun", "value", "true"),
                Map.of("key", "onelake.rerun_task", "value", taskKey),
                Map.of("key", "onelake.rerun_mode", "value", mode.name())
        );
    }

    private String activePipelineDagsterJob() {
        return isGraphPipelineExecutionMode() ? PIPELINE_GRAPH_JOB_NAME : PIPELINE_JOB_NAME;
    }

    private void validatePipelineRuntimeContract(Dag dag, String activeJobName) {
        // 先校验当前开关真正会 launch 的 job，避免 GRAPH job 缺失时先写入 JobRun/TaskRun 再失败。
        @SuppressWarnings("unchecked")
        Map<String, Object> definition = dag.getDefinition() == null || dag.getDefinition().isBlank()
                ? Map.of()
                : JsonUtil.fromJson(dag.getDefinition(), Map.class);
        runtimeContractService.launchBlockedReason(activeJobName, definition)
                .ifPresent(reason -> {
                    throw new BizException(40012, reason);
                });
    }

    private boolean isGraphPipelineExecutionMode() {
        return "GRAPH".equalsIgnoreCase(pipelineExecutionMode);
    }

    private String pipelineEngineTag(List<PipelineTask> tasks) {
        return EngineType.SPARK_SQL.name();
    }

    /**
     * Publish {@code pipeline.run.succeeded} or {@code pipeline.run.failed} Outbox event.
     *
     * <p>Called from {@link #triggerPipelineRun} on launch failure and from
     * {@code refreshRunStatus} on terminal state (next iteration).
     *
     * <p><b>C4</b>: pipeline run events replace direct writes into modeling-owned
     * runtime tables.
     */
    public void publishPipelineRunEvent(Dag dag, JobRun run, PipelineCompileResult plan,
                                        boolean succeeded, String errorMessage) {
        OutboxPublisher publisher = outboxPublisher.getIfAvailable();
        if (publisher == null) {
            log.warn("OutboxPublisher not available — skipping pipeline.run.* event for runId={}", run.getId());
            return;
        }
        UUID tenantId = TenantContext.getTenantId();
        String eventType = succeeded
                ? DomainEvents.PIPELINE_RUN_SUCCEEDED
                : DomainEvents.PIPELINE_RUN_FAILED;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("pipelineId", dag.getId().toString());
        payload.put("tenantId", tenantId == null ? null : tenantId.toString());
        payload.put("runId", run.getId().toString());
        payload.put("dagsterRunId", run.getDagsterRunId());
        payload.put("triggerType", run.getTriggerType().name());
        payload.put("startedAt", run.getStartedAt() == null ? null : run.getStartedAt().toString());
        payload.put("finishedAt", run.getFinishedAt() == null ? null : run.getFinishedAt().toString());
        long durationMs = (run.getStartedAt() == null || run.getFinishedAt() == null)
                ? 0L : Math.max(0L, run.getFinishedAt().toEpochMilli() - run.getStartedAt().toEpochMilli());
        payload.put("durationMs", durationMs);
        if (plan != null) {
            payload.put("taskCount", plan.tasks().size());
        }
        if (!succeeded) {
            payload.put("errorMsg", truncate(errorMessage, 2000));
            payload.put("partialSucceeded", List.of()); // populated by refreshRunStatus in later iteration
        }
        publisher.publish(eventType, dag.getId().toString(), payload);
    }

    @Transactional
    public Page<JobRunDTO> runs(UUID dagId, Pageable pageable) {
        Dag dag = findTenantDag(dagId);
        return runRepo.findByDagIdOrderByStartedAtDesc(dagId, pageable)
            .map(r -> toRunDTO(refreshRunStatus(r), dag));
    }

    @Transactional
    public Page<JobRunDTO> listRuns(Pageable pageable) {
        List<Dag> dags = dagRepo.findByTenantId(TenantContext.getTenantId());
        if (dags.isEmpty()) {
            return Page.empty(pageable);
        }
        Map<UUID, Dag> dagById = dags.stream()
            .collect(Collectors.toMap(Dag::getId, Function.identity()));
        return runRepo.findByDagIdInOrderByStartedAtDesc(dagById.keySet(), pageable)
            .map(r -> toRunDTO(refreshRunStatus(r), dagById.get(r.getDagId())));
    }

    @Transactional
    public JobRunDTO getRun(UUID runId) {
        List<Dag> dags = dagRepo.findByTenantId(TenantContext.getTenantId());
        if (dags.isEmpty()) {
            throw new BizException(40400, "运行实例不存在");
        }
        Map<UUID, Dag> dagById = dags.stream()
                .collect(Collectors.toMap(Dag::getId, Function.identity()));
        JobRun run = runRepo.findByIdAndDagIdIn(runId, dagById.keySet())
                .orElseThrow(() -> new BizException(40400, "运行实例不存在"));
        return toRunDTO(refreshRunStatus(run), dagById.get(run.getDagId()));
    }

    @Transactional
    public JobRunDTO cancelRun(UUID runId) {
        List<Dag> dags = dagRepo.findByTenantId(TenantContext.getTenantId());
        if (dags.isEmpty()) {
            throw new BizException(40400, "运行实例不存在");
        }
        Map<UUID, Dag> dagById = dags.stream()
                .collect(Collectors.toMap(Dag::getId, Function.identity()));
        JobRun run = runRepo.findByIdAndDagIdInForUpdate(runId, dagById.keySet())
                .orElseThrow(() -> new BizException(40400, "运行实例不存在"));
        Dag dag = dagById.get(run.getDagId());

        if (isTerminal(run.getStatus())) {
            return toRunDTO(run, dag);
        }

        if (StringUtils.hasText(run.getDagsterRunId())) {
            try {
                dagster.terminate(run.getDagsterRunId(), false);
            } catch (RuntimeException ex) {
                log.warn("Dagster terminate failed for {}: {}; marking run cancelled locally",
                        run.getDagsterRunId(), ex.getMessage());
            }
        }

        Instant cancelledAt = Instant.now();
        run.setStatus(DagStatus.CANCELLED);
        run.setFinishedAt(cancelledAt);
        run.setUpdatedAt(cancelledAt);
        runRepo.save(run);

        List<TaskRun> taskRuns = taskRunRepo.findByJobRunIdForUpdate(runId);
        for (TaskRun tr : taskRuns) {
            if (isTerminalTaskRunStatus(tr.getStatus())) {
                continue;
            }
            tr.setStatus(TaskRunStatus.CANCELLED);
            if (tr.getStartedAt() == null) {
                tr.setStartedAt(run.getStartedAt());
            }
            tr.setFinishedAt(cancelledAt);
            tr.setUpdatedAt(cancelledAt);
            taskRunRepo.save(tr);
        }
        publishPipelineRunEvent(dag, run, null, false, "cancelled");
        return toRunDTO(run, dag);
    }

    /**
     * 应用 Dagster 节点级状态回调。
     *
     * <p>租户身份从 runId 反查到 JobRun/Dag，不接受请求体传入的租户信息；
     * 方法只更新 task_run，不直接推进 JobRun 聚合状态，后者仍以 Dagster run 刷新为准。
     */
    @Transactional
    public TaskRunCallbackResult applyTaskRunCallback(UUID runId, String taskKey,
                                                      TaskRunCallbackRequest request) {
        if (request == null || request.status() == null) {
            throw new BizException(40020, "task_run 回调 status 不能为空");
        }
        if (!StringUtils.hasText(taskKey)) {
            throw new BizException(40021, "taskKey 不能为空");
        }
        JobRun run = runRepo.findById(runId)
                .orElseThrow(() -> new BizException(40400, "运行实例不存在"));
        Dag dag = dagRepo.findById(run.getDagId())
                .orElseThrow(() -> new BizException(40400, "DAG 不存在"));
        TaskRunStatus requested = request.status();
        List<TaskRun> lockedTaskRuns = null;
        TaskRun taskRun;
        if (shouldShortCircuitDownstream(requested)) {
            // 失败类回调会连带短路下游；先按固定顺序锁住整批行，避免并发回调覆盖下游状态。
            lockedTaskRuns = new ArrayList<>(taskRunRepo.findByJobRunIdForUpdate(runId));
            taskRun = lockedTaskRuns.stream()
                    .filter(tr -> taskKey.equals(tr.getTaskKey()))
                    .findFirst()
                    .orElseThrow(() -> new BizException(40400, "task_run 不存在: " + taskKey));
        } else {
            // 非短路回调只锁住当前节点行，防止迟到 RUNNING 回调覆盖已经提交的终态回调。
            taskRun = taskRunRepo.findByJobRunIdAndTaskKeyForUpdate(runId, taskKey)
                    .orElseThrow(() -> new BizException(40400, "task_run 不存在: " + taskKey));
        }
        if (dag.getTenantId() != null && taskRun.getTenantId() != null
                && !dag.getTenantId().equals(taskRun.getTenantId())) {
            throw new BizException(40400, "task_run 不存在: " + taskKey);
        }
        if (request.attempt() != null && request.attempt() < 1) {
            throw new BizException(40022, "attempt 必须大于等于 1");
        }

        TaskRunStatus current = taskRun.getStatus();
        // 单调状态机：终态不可变，低 rank 或低 attempt 的迟到回调不允许把节点状态倒退。
        if (isTerminalTaskRunStatus(current)
                || taskRunStatusRank(requested) < taskRunStatusRank(current)
                || (request.attempt() != null && request.attempt() < taskRun.getAttempt())) {
            return new TaskRunCallbackResult(false, current);
        }

        applyTaskRunCallbackFields(run, taskRun, requested, request);
        taskRun.setStatus(requested);
        taskRun.setUpdatedAt(Instant.now());
        taskRunRepo.save(taskRun);

        if (shouldShortCircuitDownstream(requested)) {
            // 失败/跳过类终态一到达，就以当前节点为种子实时短路仍在 QUEUED 的下游。
            propagateUpstreamFailures(run, lockedTaskRuns, new HashSet<>(Set.of(taskKey)));
        }
        return new TaskRunCallbackResult(true, taskRun.getStatus());
    }

    @Transactional(readOnly = true)
    public TaskRunLogResource readTaskRunLog(UUID dagId, UUID runId, String taskKey, Integer tailLines) {
        if (!StringUtils.hasText(taskKey)) {
            throw new BizException(40021, "taskKey 不能为空");
        }
        if (tailLines != null && tailLines < 0) {
            throw new BizException(40023, "tail 必须大于等于 0");
        }
        if (tailLines != null && tailLines > MAX_LOG_TAIL_LINES) {
            throw new BizException(40023, "tail 不能超过 " + MAX_LOG_TAIL_LINES);
        }
        Dag dag = findTenantDag(dagId);
        runRepo.findByIdAndDagIdIn(runId, Set.of(dag.getId()))
                .orElseThrow(() -> new BizException(40400, "运行实例不存在"));
        TaskRun taskRun = taskRunRepo.findByJobRunIdAndTaskKey(runId, taskKey)
                .orElseThrow(() -> new BizException(40400, "task_run 不存在: " + taskKey));
        if (dag.getTenantId() != null && taskRun.getTenantId() != null
                && !dag.getTenantId().equals(taskRun.getTenantId())) {
            throw new BizException(40400, "task_run 不存在: " + taskKey);
        }
        if (!StringUtils.hasText(taskRun.getLogRef())) {
            throw new BizException(40420, "节点日志不存在");
        }

        String objectKey = validateTaskLogRef(dag.getTenantId(), runId, taskRun.getLogRef());
        String filename = taskKey + ".log";
        if (tailLines != null && tailLines > 0) {
            byte[] bytes = tailLogBytes(pipelineLogStorage.open(objectKey), tailLines);
            return new TaskRunLogResource(objectKey, filename, bytes.length, new ByteArrayInputStream(bytes));
        }
        long size = pipelineLogStorage.size(objectKey);
        return new TaskRunLogResource(objectKey, filename, size, pipelineLogStorage.open(objectKey));
    }

    private void validateDag(String name, Map<String, Object> definition) {
        if (name == null || name.isBlank()) {
            throw new BizException(40020, "DAG 名称不能为空");
        }
        if (definition == null || definition.isEmpty()) {
            throw new BizException(40021, "DAG definition 不能为空");
        }
    }

    private Dag findTenantDag(UUID id) {
        return dagRepo.findByIdAndTenantId(id, TenantContext.getTenantId())
            .orElseThrow(() -> new BizException(40400, "DAG 不存在"));
    }

    private DagDTO toDTO(Dag d) {
        return toDTO(d, null);
    }

    private DagDTO toDTOWithLastRun(Dag d) {
        JobRun latestRun = runRepo.findFirstByDagIdOrderByStartedAtDesc(d.getId())
            .map(this::refreshRunStatus)
            .orElse(null);
        return toDTO(d, latestRun);
    }

    private JobRun refreshRunStatus(JobRun run) {
        if (run == null || !StringUtils.hasText(run.getDagsterRunId())) {
            return run;
        }
        run = runRepo.findByIdForUpdate(run.getId()).orElse(run);
        if (!StringUtils.hasText(run.getDagsterRunId())) {
            return run;
        }
        DagStatus oldStatus = run.getStatus();
        if (isTerminal(run.getStatus())) {
            try {
                DagStatus reconciled = syncTaskRunsFromTerminalRun(run, run.getStatus());
                if (reconciled != run.getStatus()) {
                    run.setStatus(reconciled);
                    runRepo.save(run);
                }
            } catch (RuntimeException e) {
                log.warn("Pipeline task run status sync failed for {}: {}", run.getDagsterRunId(), e.getMessage());
            }
            return run;
        }
        try {
            DagsterClient.RunStatus status = dagster.getRunStatus(run.getDagsterRunId());
            DagStatus mapped = mapDagsterStatus(status.status());
            run.setStatus(mapped);
            if (status.startedAt() != null) {
                run.setStartedAt(status.startedAt());
            }
            if (isTerminal(mapped)) {
                run.setFinishedAt(status.finishedAt() == null ? Instant.now() : status.finishedAt());
            }
            runRepo.save(run);

            // P4-A: publish pipeline.run.succeeded/failed the first time a v2 pipeline enters terminal state.
            if (isTerminal(mapped) && !isTerminal(oldStatus)) {
                DagStatus reconciled = syncTaskRunsFromTerminalRun(run, mapped);
                if (reconciled != mapped) {
                    run.setStatus(reconciled);
                    runRepo.save(run);
                }
                publishPipelineRunEventsIfTerminal(run, reconciled);
            }
        } catch (RuntimeException e) {
            log.warn("Dagster run status refresh failed for {}: {}", run.getDagsterRunId(), e.getMessage());
        }
        return run;
    }

    /**
     * Terminal refresh: Dagster runs the Spark pipeline as one fixed op. Until per-node
     * Spark artifacts are parsed, every non-terminal task_run follows the Dagster run
     * terminal state so the UI and Outbox do not stay stuck at QUEUED.
     */
    private DagStatus syncTaskRunsFromTerminalRun(JobRun run, DagStatus terminalStatus) {
        if (isGraphPipelineExecutionMode()) {
            return compensateGraphTaskRunsFromTerminalRun(run, terminalStatus);
        }
        TaskRunStatus mapped = mapTerminalTaskRunStatus(terminalStatus);
        List<TaskRun> taskRuns = taskRunRepo.findByJobRunId(run.getId());
        Set<String> failedOrBlocked = failedOrBlockedTaskKeys(taskRuns);
        if (mapped == TaskRunStatus.FAILED && !failedOrBlocked.isEmpty()) {
            propagateUpstreamFailures(run, taskRuns, failedOrBlocked);
        }
        for (TaskRun tr : taskRuns) {
            PipelineTask task = pipelineTaskRepo.findByDagIdAndTaskKey(run.getDagId(), tr.getTaskKey()).orElse(null);
            if (isTerminalTaskRunStatus(tr.getStatus())) {
                if (tr.getStatus() == TaskRunStatus.SUCCEEDED
                        && needsTaskRunSummary(tr)
                        && enrichTaskRunSummary(task, tr)) {
                    taskRunRepo.save(tr);
                }
                continue;
            }
            tr.setStatus(mapped);
            if (tr.getStartedAt() == null) {
                tr.setStartedAt(run.getStartedAt());
            }
            tr.setFinishedAt(run.getFinishedAt() == null ? Instant.now() : run.getFinishedAt());
            if (mapped == TaskRunStatus.FAILED && !StringUtils.hasText(tr.getErrorMsg())) {
                tr.setErrorMsg("Dagster run reported " + terminalStatus.name());
            }
            if (mapped == TaskRunStatus.SUCCEEDED) {
                enrichTaskRunSummary(task, tr);
            }
            taskRunRepo.save(tr);
        }
        return terminalStatus;
    }

    private DagStatus compensateGraphTaskRunsFromTerminalRun(JobRun run, DagStatus terminalStatus) {
        TaskRunStatus mapped = mapTerminalTaskRunStatus(terminalStatus);
        // GRAPH 模式可能同时收到节点回调和 run 终态兜底；锁住整批 task_run 后再补偿。
        List<TaskRun> taskRuns = taskRunRepo.findByJobRunIdForUpdate(run.getId());
        Set<String> failedOrBlocked = failedOrBlockedTaskKeys(taskRuns);
        if (mapped == TaskRunStatus.FAILED && !failedOrBlocked.isEmpty()) {
            propagateUpstreamFailures(run, taskRuns, failedOrBlocked);
        }
        for (TaskRun tr : taskRuns) {
            if (isTerminalTaskRunStatus(tr.getStatus())) {
                continue;
            }
            PipelineTask task = pipelineTaskRepo.findByDagIdAndTaskKey(run.getDagId(), tr.getTaskKey()).orElse(null);
            tr.setStatus(mapped);
            if (tr.getStartedAt() == null) {
                tr.setStartedAt(run.getStartedAt());
            }
            tr.setFinishedAt(run.getFinishedAt() == null ? Instant.now() : run.getFinishedAt());
            if (mapped == TaskRunStatus.SUCCEEDED) {
                enrichTaskRunSummary(task, tr);
            } else if (mapped == TaskRunStatus.FAILED && !StringUtils.hasText(tr.getErrorMsg())) {
                tr.setErrorMsg("Dagster graph run reported " + terminalStatus.name() + " before node callback completed");
            }
            taskRunRepo.save(tr);
        }
        return reconcileGraphRunStatusFromTaskRuns(taskRuns, terminalStatus);
    }

    private TaskRunStatus mapTerminalTaskRunStatus(DagStatus terminalStatus) {
        if (terminalStatus == DagStatus.SUCCEEDED) {
            return TaskRunStatus.SUCCEEDED;
        }
        if (terminalStatus == DagStatus.CANCELLED) {
            return TaskRunStatus.CANCELLED;
        }
        return TaskRunStatus.FAILED;
    }

    private DagStatus reconcileGraphRunStatusFromTaskRuns(List<TaskRun> taskRuns, DagStatus terminalStatus) {
        if (taskRuns == null || taskRuns.isEmpty()) {
            return terminalStatus;
        }
        if (terminalStatus == DagStatus.CANCELLED) {
            return DagStatus.CANCELLED;
        }
        boolean allSucceeded = true;
        boolean hasCancelled = false;
        for (TaskRun tr : taskRuns) {
            TaskRunStatus status = tr.getStatus();
            if (status != TaskRunStatus.SUCCEEDED) {
                allSucceeded = false;
            }
            // 子图重跑成功只代表本次提交的子图完成，旧的失败/上游失败节点仍决定整条运行失败。
            if (status == TaskRunStatus.FAILED
                    || status == TaskRunStatus.UPSTREAM_FAILED
                    || status == TaskRunStatus.SKIPPED) {
                return DagStatus.FAILED;
            }
            if (status == TaskRunStatus.CANCELLED) {
                hasCancelled = true;
            }
        }
        if (hasCancelled) {
            return DagStatus.CANCELLED;
        }
        if (terminalStatus == DagStatus.SUCCEEDED && allSucceeded) {
            return DagStatus.SUCCEEDED;
        }
        return terminalStatus;
    }

    private Set<String> failedOrBlockedTaskKeys(List<TaskRun> taskRuns) {
        Set<String> keys = new HashSet<>();
        for (TaskRun tr : taskRuns) {
            if (tr.getStatus() == TaskRunStatus.FAILED
                    || tr.getStatus() == TaskRunStatus.UPSTREAM_FAILED
                    || tr.getStatus() == TaskRunStatus.SKIPPED) {
                keys.add(tr.getTaskKey());
            }
        }
        return keys;
    }

    private void propagateUpstreamFailures(JobRun run, List<TaskRun> taskRuns, Set<String> failedOrBlocked) {
        if (failedOrBlocked.isEmpty()) {
            return;
        }
        Map<String, TaskRun> runByTaskKey = taskRuns.stream()
                .collect(Collectors.toMap(TaskRun::getTaskKey, Function.identity(), (a, b) -> a,
                        LinkedHashMap::new));
        List<PipelineTaskEdge> allEdges = pipelineTaskEdgeRepo.findByDagId(run.getDagId());
        if (allEdges == null) {
            allEdges = List.of();
        }
        List<PipelineTaskEdge> pipelineEdges = allEdges.stream()
                .filter(edge -> edge.getEdgeLayer() == EdgeLayer.PIPELINE)
                .toList();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (PipelineTaskEdge edge : pipelineEdges) {
                if (!failedOrBlocked.contains(edge.getSourceKey())) {
                    continue;
                }
                TaskRun downstream = runByTaskKey.get(edge.getTargetKey());
                if (downstream == null || downstream.getStatus() != TaskRunStatus.QUEUED) {
                    continue;
                }
                downstream.setStatus(TaskRunStatus.UPSTREAM_FAILED);
                downstream.setErrorMsg("Upstream task failed: " + edge.getSourceKey());
                downstream.setFinishedAt(run.getFinishedAt() == null ? Instant.now() : run.getFinishedAt());
                taskRunRepo.save(downstream);
                failedOrBlocked.add(edge.getTargetKey());
                changed = true;
            }
        }
    }

    private boolean isTerminalTaskRunStatus(TaskRunStatus status) {
        return status == TaskRunStatus.SUCCEEDED
                || status == TaskRunStatus.FAILED
                || status == TaskRunStatus.CANCELLED
                || status == TaskRunStatus.UPSTREAM_FAILED
                || status == TaskRunStatus.SKIPPED;
    }

    private boolean isRerunnableTaskRunStatus(TaskRunStatus status) {
        return status == TaskRunStatus.FAILED
                || status == TaskRunStatus.UPSTREAM_FAILED;
    }

    private int taskRunStatusRank(TaskRunStatus status) {
        if (status == null) {
            return -1;
        }
        // 仅表达“进度单调性”，不区分不同终态之间的优先级；终态保护由调用方先判断。
        return switch (status) {
            case QUEUED -> 0;
            case RUNNING -> 1;
            case SUCCEEDED, FAILED, CANCELLED, UPSTREAM_FAILED, SKIPPED -> 2;
        };
    }

    private boolean shouldShortCircuitDownstream(TaskRunStatus status) {
        return status == TaskRunStatus.FAILED
                || status == TaskRunStatus.UPSTREAM_FAILED
                || status == TaskRunStatus.SKIPPED;
    }

    private void applyTaskRunCallbackFields(JobRun run, TaskRun taskRun, TaskRunStatus requested,
                                            TaskRunCallbackRequest request) {
        Instant now = Instant.now();
        // 可选字段只在回调携带时覆盖，避免较早的空值回调冲掉已写入的观测信息。
        if (request.attempt() != null) {
            taskRun.setAttempt(Math.max(taskRun.getAttempt(), request.attempt()));
        }
        if (request.logRef() != null) {
            taskRun.setLogRef(truncate(request.logRef(), 512));
        }
        if (request.dagsterStepKey() != null) {
            taskRun.setDagsterStepKey(truncate(request.dagsterStepKey(), 128));
        }
        if (request.rowsWritten() != null) {
            taskRun.setRowsWritten(request.rowsWritten());
        }
        if (request.scanBytes() != null) {
            taskRun.setScanBytes(request.scanBytes());
        }
        if (request.artifactPath() != null) {
            taskRun.setArtifactPath(truncate(request.artifactPath(), 512));
        }
        if (request.errorMsg() != null) {
            taskRun.setErrorMsg(truncate(request.errorMsg(), 4000));
        }
        if (request.startedAt() != null) {
            taskRun.setStartedAt(request.startedAt());
        } else if ((requested == TaskRunStatus.RUNNING || isTerminalTaskRunStatus(requested))
                && taskRun.getStartedAt() == null) {
            // Dagster 未传 startedAt 时，用 JobRun 开始时间作为节点运行的保守起点。
            taskRun.setStartedAt(run.getStartedAt() == null ? now : run.getStartedAt());
        }
        if (request.finishedAt() != null) {
            taskRun.setFinishedAt(request.finishedAt());
        } else if (isTerminalTaskRunStatus(requested) && taskRun.getFinishedAt() == null) {
            // 终态回调必须让 task_run 可观测地收口；缺失 finishedAt 时用服务端时间兜底。
            taskRun.setFinishedAt(now);
        }
    }

    private boolean needsTaskRunSummary(TaskRun tr) {
        return tr.getRowsWritten() == null || !StringUtils.hasText(tr.getArtifactPath());
    }

    private boolean enrichTaskRunSummary(PipelineTask task, TaskRun tr) {
        TaskRunSummary summary = taskRunSummary(task);
        if (summary == null) {
            return false;
        }
        boolean changed = false;
        if (tr.getRowsWritten() == null && summary.rowsWritten() != null) {
            tr.setRowsWritten(summary.rowsWritten());
            changed = true;
        }
        if (!StringUtils.hasText(tr.getArtifactPath()) && StringUtils.hasText(summary.artifactPath())) {
            tr.setArtifactPath(summary.artifactPath());
            changed = true;
        }
        return changed;
    }

    private TaskRunSummary taskRunSummary(PipelineTask task) {
        if (task == null || task.getTaskType() == null) {
            return null;
        }
        if (task.getTaskType() == TaskType.QUALITY_GATE) {
            String qualityFqn = qualityTableFqn(task);
            return new TaskRunSummary(
                    catalogRowCount(task.getTenantId(), qualityFqn),
                    StringUtils.hasText(qualityFqn) ? "quality:" + qualityFqn : null);
        }
        String targetFqn = normalizeTableFqn(task.getTargetFqn());
        return new TaskRunSummary(
                catalogRowCount(task.getTenantId(), targetFqn),
                StringUtils.hasText(targetFqn) ? "table:" + targetFqn : null);
    }

    private String qualityTableFqn(PipelineTask task) {
        JsonNode config = parseTaskConfig(task);
        String configured = firstText(
                config.path("qualityTableFqn").asText(""),
                config.path("quality_table_fqn").asText(""));
        if (StringUtils.hasText(configured)) {
            return normalizeTableFqn(configured);
        }
        String targetFqn = normalizeTableFqn(task.getTargetFqn());
        if (!StringUtils.hasText(targetFqn)) {
            return null;
        }
        String[] parts = targetFqn.split("\\.");
        if (parts.length != 2) {
            return null;
        }
        return parts[0] + "." + parts[1] + "_quality_check";
    }

    private Long catalogRowCount(UUID tenantId, String fqn) {
        if (tenantId == null || !StringUtils.hasText(fqn)) {
            return null;
        }
        try {
            return jdbc.query("""
                    select row_count
                    from catalog.asset
                    where tenant_id = ? and om_fqn = ?
                    """,
                    ps -> {
                        ps.setObject(1, tenantId);
                        ps.setString(2, fqn);
                    },
                    rs -> rs.next() ? rs.getObject("row_count", Long.class) : null);
        } catch (RuntimeException e) {
            log.warn("Task run summary row_count lookup skipped for {}: {}", fqn, e.getMessage());
            return null;
        }
    }

    private String normalizeTableFqn(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String fqn = value.trim().replace("\"", "");
        String[] parts = fqn.split("\\.");
        if (parts.length >= 3) {
            return parts[parts.length - 2] + "." + parts[parts.length - 1];
        }
        if (parts.length == 2) {
            return parts[0] + "." + parts[1];
        }
        return fqn;
    }

    /**
     * P4-A — emit {@code pipeline.run.succeeded/failed} (+ per-task {@code pipeline.task.loaded})
     * the first time a v2 pipeline run reaches terminal state.
     *
     */
    private void publishPipelineRunEventsIfTerminal(JobRun run, DagStatus terminalStatus) {
        try {
            Dag dag = dagRepo.findById(run.getDagId()).orElse(null);
            if (dag == null) return;
            // Only v2 pipeline job
            if (!PIPELINE_JOB_NAME.equals(dag.getDagsterJob())
                    && !PIPELINE_GRAPH_JOB_NAME.equals(dag.getDagsterJob())) return;

            boolean succeeded = terminalStatus == DagStatus.SUCCEEDED;
            // 1. pipeline.task.loaded for each executable task_run in this job
            List<TaskRun> taskRuns = taskRunRepo.findByJobRunId(run.getId());
            for (TaskRun tr : taskRuns) {
                publishPipelineTaskLoadedEvent(dag, run, tr, succeeded);
            }
            // 2. pipeline.run.succeeded/failed (aggregate)
            publishPipelineRunEvent(dag, run, null, succeeded,
                    pipelineRunTerminalErrorMessage(terminalStatus));
            log.info("Pipeline {} run {} terminal event published: {}",
                    dag.getId(), run.getId(), succeeded ? "SUCCEEDED" : "FAILED");
        } catch (RuntimeException e) {
            log.warn("publishPipelineRunEventsIfTerminal failed for run {}: {}",
                    run.getId(), e.getMessage());
        }
    }

    private String pipelineRunTerminalErrorMessage(DagStatus terminalStatus) {
        if (terminalStatus == DagStatus.SUCCEEDED) {
            return null;
        }
        if (terminalStatus == DagStatus.CANCELLED) {
            return "cancelled";
        }
        return "Dagster run reported failure";
    }

    private void publishPipelineTaskLoadedEvent(Dag dag, JobRun run, TaskRun tr, boolean runSucceeded) {
        OutboxPublisher publisher = outboxPublisher.getIfAvailable();
        if (publisher == null) return;
        if (tr.getStatus() == null) return;

        UUID tenantId = run.getTriggeredBy() != null ? dag.getTenantId() : dag.getTenantId();
        // Only emit task.loaded for SUCCEEDED task_runs; failed tasks surface via pipeline.run.failed
        if (tr.getStatus() != com.onelake.orchestration.domain.enums.TaskRunStatus.SUCCEEDED) return;

        java.util.Map<String, Object> payload = new LinkedHashMap<>();
        PipelineTask task = pipelineTaskRepo.findByDagIdAndTaskKey(dag.getId(), tr.getTaskKey()).orElse(null);
        if (!isMaterializingTask(task)) {
            return;
        }
        JsonNode taskConfig = parseTaskConfig(task);
        payload.put("pipelineId", dag.getId().toString());
        payload.put("tenantId", tenantId.toString());
        payload.put("runId", run.getId().toString());
        payload.put("taskKey", tr.getTaskKey());
        payload.put("taskType", task == null || task.getTaskType() == null ? null : task.getTaskType().name());
        payload.put("taskName", task == null ? tr.getTaskKey() : task.getName());
        payload.put("engine", task == null ? EngineType.SPARK_SQL.name() : task.getEngine());
        payload.put("targetFqn", task == null ? null : task.getTargetFqn());
        payload.put("modelId", task == null || task.getModelId() == null ? null : task.getModelId().toString());
        if (run.getTriggeredBy() != null) payload.put("ownerId", run.getTriggeredBy().toString());
        String ownerName = TenantContext.getUsername();
        if (StringUtils.hasText(ownerName)) payload.put("ownerName", ownerName);
        List<String> fromTables = textArray(taskConfig.path("from_tables"));
        if (fromTables.isEmpty()) {
            fromTables = textArray(taskConfig.path("fromTables"));
        }
        if (!fromTables.isEmpty()) payload.put("fromTables", fromTables);
        JsonNode catalog = taskConfig.path("catalog");
        if (catalog != null && catalog.isObject()) {
            payload.put("catalog", JsonUtil.mapper().convertValue(catalog, Object.class));
        }
        if (tr.getRowsWritten() != null) payload.put("rowsWritten", tr.getRowsWritten());
        if (tr.getScanBytes() != null) payload.put("scanBytes", tr.getScanBytes());
        if (tr.getArtifactPath() != null) payload.put("artifactPath", tr.getArtifactPath());
        payload.put("finishedAt", tr.getFinishedAt() == null ? null : tr.getFinishedAt().toString());
        publisher.publish(DomainEvents.PIPELINE_TASK_LOADED, dag.getId().toString(), payload);
    }

    private boolean isMaterializingTask(PipelineTask task) {
        if (task == null || task.getTaskType() == null) {
            return false;
        }
        return switch (task.getTaskType()) {
            case SPARK_SQL, PYSPARK, QUALITY_GATE -> true;
            case SYNC_REF -> false;
        };
    }

    private JsonNode parseTaskConfig(PipelineTask task) {
        if (task == null || !StringUtils.hasText(task.getConfig())) {
            return JsonUtil.mapper().createObjectNode();
        }
        try {
            return JsonUtil.parse(task.getConfig());
        } catch (RuntimeException e) {
            log.warn("Pipeline task {} config parse skipped: {}", task.getTaskKey(), e.getMessage());
            return JsonUtil.mapper().createObjectNode();
        }
    }

    private List<String> textArray(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return values;
        }
        for (JsonNode item : node) {
            String text = item.asText("");
            if (StringUtils.hasText(text)) {
                values.add(text.trim());
            }
        }
        return values;
    }

    private DagStatus mapDagsterStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return DagStatus.RUNNING;
        }
        return switch (status.trim().toUpperCase()) {
            case "SUCCESS", "SUCCEEDED" -> DagStatus.SUCCEEDED;
            case "CANCELED", "CANCELLED" -> DagStatus.CANCELLED;
            case "FAILURE", "FAILED" -> DagStatus.FAILED;
            case "QUEUED", "NOT_STARTED" -> DagStatus.QUEUED;
            default -> DagStatus.RUNNING;
        };
    }

    private boolean isTerminal(DagStatus status) {
        return status == DagStatus.SUCCEEDED || status == DagStatus.FAILED || status == DagStatus.CANCELLED;
    }

    private DagDTO toDTO(Dag d, JobRun latestRun) {
        @SuppressWarnings("unchecked")
        Map<String, Object> definition = d.getDefinition() == null || d.getDefinition().isBlank()
            ? Map.of()
            : JsonUtil.fromJson(d.getDefinition(), Map.class);
        TriggerReadiness readiness = triggerReadiness(d, definition);
        return new DagDTO(d.getId(), d.getName(), d.getDagsterJob(), definition,
            d.getScheduleCron(), d.getEnabled(), readiness.triggerable(), readiness.reason(),
            d.getVersion(), d.getCreatedAt(),
            latestRun == null ? null : toRunDTO(latestRun, d));
    }

    private TriggerReadiness triggerReadiness(Dag dag) {
        @SuppressWarnings("unchecked")
        Map<String, Object> definition = dag.getDefinition() == null || dag.getDefinition().isBlank()
            ? Map.of()
            : JsonUtil.fromJson(dag.getDefinition(), Map.class);
        return triggerReadiness(dag, definition);
    }

    private TriggerReadiness triggerReadiness(Dag dag, Map<String, Object> definition) {
        if (!Boolean.TRUE.equals(dag.getEnabled())) {
            return new TriggerReadiness(false, "DAG 仍是草稿，需启用后才能触发");
        }
        String dagsterJob = dag.getDagsterJob();
        if (dagsterJob == null || dagsterJob.isBlank()) {
            return new TriggerReadiness(false, "DAG 未配置 Dagster 作业");
        }
        if (DRAFT_DAGSTER_JOB.equals(dagsterJob)) {
            return new TriggerReadiness(false, "当前为画布草稿，尚未绑定可执行 Dagster 作业");
        }
        return runtimeContractService.triggerBlockedReason(dagsterJob, definition)
            .map(reason -> new TriggerReadiness(false, reason))
            .orElseGet(() -> new TriggerReadiness(true, null));
    }

    private String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private String validateTaskLogRef(UUID tenantId, UUID runId, String logRef) {
        String objectKey = logRef == null ? "" : logRef.trim();
        String expectedPrefix = tenantId + "/" + runId + "/";
        if (!StringUtils.hasText(objectKey)
                || objectKey.startsWith("/")
                || objectKey.contains("..")
                || !objectKey.startsWith(expectedPrefix)) {
            throw new BizException(40024, "节点日志引用非法");
        }
        return objectKey;
    }

    private byte[] tailLogBytes(InputStream inputStream, int tailLines) {
        Deque<String> lines = new ArrayDeque<>(Math.max(1, tailLines));
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (lines.size() == tailLines) {
                    lines.removeFirst();
                }
                lines.addLast(line);
            }
        } catch (IOException e) {
            throw new com.onelake.common.exception.DataplaneException("读取节点日志失败", e);
        }
        String text = String.join("\n", lines);
        if (!text.isEmpty()) {
            text += "\n";
        }
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private String currentTriggerActorName() {
        String username = TenantContext.getUsername();
        return StringUtils.hasText(username) ? username.trim() : "system";
    }

    private String displayTriggerActor(JobRun run) {
        if (StringUtils.hasText(run.getTriggeredByName())) {
            return run.getTriggeredByName().trim();
        }
        if (run.getTriggeredBy() == null) {
            return "system";
        }
        UUID currentUser = TenantContext.getUserId();
        String currentUsername = TenantContext.getUsername();
        if (currentUser != null && currentUser.equals(run.getTriggeredBy()) && StringUtils.hasText(currentUsername)) {
            return currentUsername.trim();
        }
        return "未知用户";
    }

    private JobRunDTO toRunDTO(JobRun r, Dag dag) {
        return new JobRunDTO(r.getId(), r.getDagId(),
            dag == null ? null : dag.getName(),
            dag == null ? null : dag.getDagsterJob(),
            r.getDagsterRunId(),
            r.getTriggerType().name(), r.getStatus().name(),
            r.getStartedAt(), r.getFinishedAt(), r.getTriggeredBy(), displayTriggerActor(r));
    }

    private record TriggerReadiness(boolean triggerable, String reason) {}

    private record TaskRunSummary(Long rowsWritten, String artifactPath) {}

    public record TaskRunLogResource(String objectKey, String filename, long contentLength, InputStream content) {}

    private enum RerunMode {
        SINGLE,
        DOWNSTREAM;

        private static RerunMode parse(String raw) {
            if (!StringUtils.hasText(raw)) {
                return SINGLE;
            }
            try {
                return RerunMode.valueOf(raw.trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                throw new BizException(40022, "mode 非法: " + raw);
            }
        }
    }
}
