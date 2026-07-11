package com.onelake.orchestration.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import com.onelake.orchestration.domain.enums.ScheduleMode;
import com.onelake.orchestration.domain.enums.TaskRunStatus;
import com.onelake.orchestration.domain.enums.TaskType;
import com.onelake.orchestration.domain.enums.TriggerType;
import com.onelake.orchestration.dto.DagDTO;
import com.onelake.orchestration.dto.JobRunDTO;
import com.onelake.orchestration.dto.PipelineCompileResult;
import com.onelake.orchestration.dto.TaskConfigRenderResult;
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
import org.springframework.data.domain.PageImpl;
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
import java.time.ZoneId;
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

/**
 * 编排运行应用服务，负责把租户内的 DAG/流水线定义转换为可观测的 Dagster 运行。
 *
 * <p>本服务同时保留通用 DAG 触发路径和流水线 V2 路径。流水线运行遵循
 * “Java 编译并生成 runConfig、Dagster 执行”的边界：Java 侧持久化
 * {@link JobRun}/{@link TaskRun}、构建运行参数并聚合状态，Dagster 侧负责实际的
 * 图执行。运行结果通过节点回调、状态轮询和 Outbox 事件与其他模块衔接。</p>
 *
 * <p>所有面向用户的查询和触发入口都必须经过租户边界；涉及节点回调、取消和重跑的
 * 写操作使用行锁与单调状态规则，防止并发回调或迟到事件覆盖已经确认的终态。</p>
 */
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

    /** 流水线 V2 的编译、节点持久化、配置构建和事件发布依赖。 */
    private final PipelineCompileService pipelineCompileService;
    private final PipelineSnapshotService pipelineSnapshotService;
    private final PipelineTaskRepository pipelineTaskRepo;
    private final PipelineTaskEdgeRepository pipelineTaskEdgeRepo;
    private final TaskRunRepository taskRunRepo;
    private final SparkRunConfigBuilder sparkBuilder;
    private final ObjectProvider<OutboxPublisher> outboxPublisher;
    private final PipelineLogStorage pipelineLogStorage;
    private final DataIntervalCalculator dataIntervalCalculator;

    /**
     * LEGACY 使用固定单 op 作业，GRAPH 使用按 pipeline task 展开的 Dagster graph。
     * 默认保持 LEGACY，便于出现运行时兼容问题时快速回退。
     */
    @Value("${onelake.orchestration.pipeline-execution-mode:LEGACY}")
    private String pipelineExecutionMode = "LEGACY";

    /** Dagster 节点向内部回调接口上报状态时使用的服务根地址。 */
    @Value("${onelake.orchestration.callback-base-url:}")
    private String pipelineCallbackBaseUrl = "";

    /** GRAPH 作业内允许同时运行的最大节点数，最终值至少为 1。 */
    @Value("${onelake.orchestration.max-parallel:4}")
    private int pipelineMaxParallel = 4;

    /**
     * 创建默认启用的通用 DAG 定义。
     *
     * @param name DAG 展示名称
     * @param dagsterJob Dagster 作业名；为空时使用草稿作业
     * @param definition DAG JSON 定义
     * @param scheduleCron 可选 Cron 表达式
     * @return 已持久化的 DAG
     */
    @Transactional
    public DagDTO createDag(String name, String dagsterJob, Map<String, Object> definition,
                            String scheduleCron) {
        return createDag(name, dagsterJob, definition, scheduleCron, true);
    }

    /**
     * 创建通用 DAG 定义，并显式指定是否启用调度。
     *
     * @param enabled {@code null} 按启用处理
     */
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

    /**
     * 更新租户内 DAG 定义并递增定义版本。
     *
     * <p>{@code dagsterJob} 为空时保留原值，{@code enabled} 为空时保留原启用状态。</p>
     */
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
        if ("PUBLISHED".equalsIgnoreCase(dag.getStatus())) {
            dag.setHasUnpublishedChanges(true);
        }
        dag.setVersion(dag.getVersion() == null ? 1 : dag.getVersion() + 1);
        dagRepo.save(dag);
        return toDTO(dag);
    }

    /** 查询租户内单个 DAG，并附带最近一次运行摘要。 */
    @Transactional
    public DagDTO getDag(UUID id) {
        return toDTOWithLastRun(findTenantDag(id));
    }

    /** 查询当前租户的全部 DAG，并为每条定义刷新最近运行状态。 */
    @Transactional
    public List<DagDTO> listDags() {
        return dagRepo.findByTenantId(TenantContext.getTenantId()).stream()
            .map(this::toDTOWithLastRun)
            .toList();
    }

    /**
     * 触发非流水线的通用 Dagster 作业。
     *
     * <p>先持久化本地运行记录，再启动 Dagster；启动失败时保留 FAILED 运行作为审计证据。
     * {@code noRollbackFor} 保证业务异常不会回滚这条失败记录。</p>
     */
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
        run.setTimezone(StringUtils.hasText(dag.getTimezone()) ? dag.getTimezone() : "Asia/Shanghai");
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
     * 流水线 V2 触发路径。
     *
     * <p>该路径与非流水线 Dagster 作业使用的通用 {@link #triggerDag(UUID, TriggerType)}
     * 并存。外部模型执行已移除，新路径按以下顺序执行：
     * <ol>
     *   <li>通过 {@link PipelineCompileService} 编译流水线。</li>
     *   <li>通过 {@link SparkRunConfigBuilder} 构建 Spark Dagster runConfig。</li>
     *   <li>启动 {@code onelake_pipeline_run} 或 {@code onelake_pipeline_graph_run} Dagster 作业。</li>
     *   <li>为每个可观测节点创建 {@link TaskRun}，并按拓扑初始化状态。</li>
     *   <li><b>不写入 modeling.* schema</b>；建模模块更新由 Outbox 事件驱动。</li>
     * </ol>
     *
     * @return JobRun ID
     */
    @Transactional(noRollbackFor = BizException.class)
    public UUID triggerPipelineRun(UUID dagId, TriggerType trigger) {
        return triggerPipelineRun(dagId, trigger, RunContext.empty(trigger));
    }

    /**
     * CRON 专用触发入口。
     *
     * <p>调度器传入实际命中的计划时刻，本服务再按 DAG 时区和分区粒度推导业务数据区间，
     * 确保主调度和 catchup 使用同一套 logical date 语义。</p>
     */
    @Transactional(noRollbackFor = BizException.class)
    public UUID triggerPipelineRun(UUID dagId, TriggerType trigger, Instant scheduledAt) {
        return triggerPipelineRun(dagId, trigger, scheduledAt, null);
    }

    /** CRON 触发时可显式固定调度器已经评估过的发布版本，避免判定与执行之间切换快照。 */
    @Transactional(noRollbackFor = BizException.class)
    public UUID triggerPipelineRun(UUID dagId,
                                   TriggerType trigger,
                                   Instant scheduledAt,
                                   UUID useVersionId) {
        if (trigger != TriggerType.CRON) {
            throw new BizException(40020, "计划时刻仅适用于 CRON 触发");
        }
        if (scheduledAt == null) {
            return triggerPipelineRun(dagId, trigger, RunContext.empty(trigger), useVersionId);
        }
        Dag liveDag = findTenantDag(dagId);
        UUID selectedVersionId = useVersionId != null
                ? useVersionId
                : liveDag.getPublishedVersionId();
        Dag dag = liveDag;
        if (selectedVersionId != null) {
            dag = pipelineSnapshotService
                    .loadExecutionSnapshot(selectedVersionId, dagId)
                    .dag();
        }
        RunContext runContext = dataIntervalCalculator
                .calculate(dag.getPartitionGrain(), scheduledAt, dag.getTimezone())
                .toRunContext(dag.getTimezone(), "NORMAL", null, trigger);
        return triggerPipelineRun(dagId, trigger, runContext, selectedVersionId);
    }
    /**
     * 使用完整运行上下文触发流水线，供正常调度和回填派发共同复用。
     *
     * <p>方法依次完成租户/运行时契约检查、流水线编译、JobRun/TaskRun 建档、runConfig
     * 构建和 Dagster 启动。CRON 运行会在远端启动前立即刷新唯一键写入，以
     * {@code (dag_id, logical_date)} 阻止重复触发。</p>
     *
     * @param context logical date、数据区间、时区和回填标识等运行上下文
     * @return 本地 JobRun ID
     */
    @Transactional(noRollbackFor = BizException.class)
    public UUID triggerPipelineRun(UUID dagId, TriggerType trigger, RunContext context) {
        return triggerPipelineRunInternal(dagId, trigger, context, null, null);
    }

    /** 显式选择发布版本触发；useVersionId 为空时仍默认使用 dag.publishedVersionId。 */
    @Transactional(noRollbackFor = BizException.class)
    public UUID triggerPipelineRun(UUID dagId,
                                   TriggerType trigger,
                                   RunContext context,
                                   UUID useVersionId) {
        return triggerPipelineRunInternal(dagId, trigger, context, null, useVersionId);
    }

    /**
     * 为一条失败 JobRun 创建新的 DAG 级自动重跑。
     *
     * <p>该入口只被 {@link PipelineRunRetryService} 调用。新运行使用 AUTO_RETRY 触发类型，
     * 继承原业务时间上下文，并通过 retrySourceRunId/runRetryAttempt 记录独立于节点重试的来源。</p>
     */
    @Transactional(noRollbackFor = BizException.class)
    public UUID triggerPipelineRetry(JobRun sourceRun) {
        if (sourceRun == null || sourceRun.getId() == null || sourceRun.getDagId() == null) {
            throw new BizException(40020, "自动重跑来源不能为空");
        }
        if (sourceRun.getStatus() != DagStatus.FAILED) {
            throw new BizException(40020, "仅失败终态可自动重跑");
        }
        int nextAttempt = Math.max(0,
                sourceRun.getRunRetryAttempt() == null ? 0 : sourceRun.getRunRetryAttempt()) + 1;
        RunContext context = new RunContext(
                sourceRun.getLogicalDate(),
                sourceRun.getDataIntervalStart(),
                sourceRun.getDataIntervalEnd(),
                sourceRun.getTimezone(),
                sourceRun.getRunMode(),
                sourceRun.getBackfillId(),
                TriggerType.AUTO_RETRY);
        RunRetryMetadata retryMetadata = new RunRetryMetadata(sourceRun.getId(), nextAttempt);
        try {
            return triggerPipelineRunInternal(
                    sourceRun.getDagId(),
                    TriggerType.AUTO_RETRY,
                    context,
                    retryMetadata,
                    sourceRun.getPipelineVersionId());
        } catch (RuntimeException ex) {
            // 运行时契约或编译可能在 JobRun 建档前失败；仍需生成失败子运行承接次数链和审计来源。
            if (!retryMetadata.runCreated()) {
                persistRejectedPipelineRetry(sourceRun, retryMetadata, ex);
            }
            if (ex instanceof BizException bizException) {
                throw bizException;
            }
            throw new BizException(50200, "自动重跑触发失败: " + ex.getMessage(), ex);
        }
    }

    private UUID triggerPipelineRunInternal(UUID dagId,
                                            TriggerType trigger,
                                            RunContext context,
                                            RunRetryMetadata retryMetadata,
                                            UUID useVersionId) {
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new BizException(40100, "Tenant context required to trigger pipeline");
        }
        Dag liveDag = findTenantDag(dagId);
        UUID selectedVersionId = useVersionId != null
                ? useVersionId
                : liveDag.getPublishedVersionId();
        if (selectedVersionId == null && "PUBLISHED".equalsIgnoreCase(liveDag.getStatus())) {
            throw new BizException(40064, "已发布流水线缺少不可变版本，请重新发布后再运行");
        }
        PipelineSnapshotService.ExecutionSnapshot executionSnapshot = selectedVersionId == null
                ? null
                : pipelineSnapshotService.loadExecutionSnapshot(selectedVersionId, dagId);
        Dag dag = executionSnapshot == null ? liveDag : executionSnapshot.dag();
        RunContext runContext = normalizeRunContext(context, dag, trigger);
        boolean dryRun = ScheduleMode.DRY_RUN.name().equalsIgnoreCase(runContext.runMode());
        String activeJobName = activePipelineDagsterJob(dagId, selectedVersionId);
        if (!dryRun && isGraphPipelineExecutionMode()) {
            pipelineSnapshotService.activateForDagster(selectedVersionId);
            dagster.reloadRepositoryLocation("onelake-loc");
        }
        if (!dryRun) {
            validatePipelineRuntimeContract(dag, activeJobName);
        }

        // 1. 编译流水线。
        List<PipelineTask> tasks;
        List<PipelineTaskEdge> pipelineEdges;
        PipelineCompileResult plan;
        if (executionSnapshot == null) {
            plan = pipelineCompileService.compile(dagId);
            // DEV 路径保持原有顺序：先编译并回写 executable/config，再读取运行输入。
            tasks = pipelineTaskRepo.findByDagIdOrderByCreatedAtAsc(dagId);
            pipelineEdges = pipelineTaskEdgeRepo.findByDagId(dagId);
        } else {
            tasks = new ArrayList<>(executionSnapshot.tasks());
            pipelineEdges = new ArrayList<>(executionSnapshot.edges());
            plan = pipelineCompileService.compile(dagId, tenantId, tasks, pipelineEdges);
        }
        if (!plan.allValidated()) {
            throw new BizException(40060, "Pipeline 编译未通过，无法触发: "
                    + String.join("; ",
                        plan.tasks().stream()
                                .filter(t -> !t.valid())
                                .map(t -> t.taskKey() + ": " + t.errorMessage())
                                .toList())
                    + (plan.graphErrors().isEmpty() ? "" : "; " + String.join("; ", plan.graphErrors())));
        }
        // 2. 就绪检查：至少需要一个真实执行节点；SYNC_REF/QUALITY_GATE 等观测节点仍会生成 task_run 供 UI 展示。
        long executableCount = tasks.stream().filter(PipelineTask::getExecutable).count();
        if (executableCount == 0) {
            throw new BizException(40061, "流水线没有可执行任务（可能是 Spark 任务尚未就绪，P-Spark 阶段后启用）");
        }

        // 3. 创建 JobRun。
        JobRun run = new JobRun();
        run.setDagId(dagId);
        run.setTriggerType(trigger);
        run.setStatus(DagStatus.QUEUED);
        run.setStartedAt(Instant.now());
        run.setTriggeredBy(TenantContext.getUserId());
        run.setTriggeredByName(currentTriggerActorName());
        run.setLogicalDate(runContext.logicalDate());
        run.setDataIntervalStart(runContext.dataIntervalStart());
        run.setDataIntervalEnd(runContext.dataIntervalEnd());
        run.setBackfillId(runContext.backfillId());
        run.setPipelineVersionId(selectedVersionId);
        run.setTimezone(runContext.timezone());
        run.setRunMode(runContext.runMode());
        run.setDagsterJob(activeJobName);
        if (retryMetadata != null) {
            run.setRetrySourceRunId(retryMetadata.sourceRunId());
            run.setRunRetryAttempt(retryMetadata.attempt());
        }
        // V14 为 CRON logical_date 建立了唯一索引。此处立即 flush，确保重复调度在
        // 启动 Dagster 前被识别，由 PipelineSchedulerService 安全地当作已触发处理。
        if (trigger == TriggerType.CRON && runContext.logicalDate() != null) {
            runRepo.saveAndFlush(run);
        } else {
            runRepo.save(run);
        }
        if (retryMetadata != null) {
            retryMetadata.markRunCreated(run.getId());
        }

        // 4. 为每个有效节点创建 TaskRun。初始状态由数据流 DAG 推导，避免所有节点被扁平化为 QUEUED。
        List<PipelineTask> observable = observableTasks(plan, tasks);
        Map<String, TaskRunStatus> initialStatuses = initialTaskRunStatuses(observable, pipelineEdges);
        for (PipelineTask t : observable) {
            TaskRun tr = new TaskRun();
            tr.setTenantId(tenantId);
            tr.setJobRunId(run.getId());
            tr.setTaskKey(t.getTaskKey());
            tr.setDataIntervalStart(runContext.dataIntervalStart());
            tr.setDataIntervalEnd(runContext.dataIntervalEnd());
            TaskRunStatus initialStatus = dryRun
                    ? TaskRunStatus.SUCCEEDED
                    : initialStatuses.getOrDefault(t.getTaskKey(), TaskRunStatus.QUEUED);
            tr.setStatus(initialStatus);
            if (initialStatus == TaskRunStatus.RUNNING || initialStatus == TaskRunStatus.SUCCEEDED) {
                tr.setStartedAt(run.getStartedAt());
            }
            if (initialStatus == TaskRunStatus.SUCCEEDED) {
                tr.setFinishedAt(run.getStartedAt());
                if (!dryRun && StringUtils.hasText(t.getTargetFqn())) {
                    tr.setArtifactPath("table:" + normalizeTableFqn(t.getTargetFqn()));
                }
            }
            taskRunRepo.save(tr);
        }

        if (dryRun) {
            // 空跑只验证编译、节点集合和拓扑建档；不构建 runConfig、不启动 Dagster，因而不会 spark-submit。
            Instant finishedAt = Instant.now();
            run.setStatus(DagStatus.SUCCEEDED);
            run.setFinishedAt(finishedAt);
            run.setUpdatedAt(finishedAt);
            runRepo.save(run);
            // 仅发布聚合成功事件供依赖侧观测；不发布 pipeline.task.loaded，避免伪造数据落表。
            publishPipelineRunEvent(dag, run, plan, true, null);
            log.info("Spark 流水线 {} 已空跑成功，runId={}，taskCount={}",
                    dagId, run.getId(), observable.size());
            return run.getId();
        }

        // 5-6. 构建 runConfig 并启动 Dagster；两步共用失败收口，避免配置构建异常留下 QUEUED 孤儿运行。
        try {
            TaskBundleContext ctx = new TaskBundleContext(
                    dagId, tenantId, run.getId(), plan,
                    plan.pipelineTag(),
                    dag.getResourceGroup() == null ? "spark-default" : dag.getResourceGroup(),
                    dag.getComputeProfile() == null ? "spark-small" : dag.getComputeProfile(),
                    executionSnapshot == null ? null : executionSnapshot.params(),
                    selectedVersionId
            );
            DagsterRunConfig runConfig = buildPipelineRunConfig(ctx, tasks, pipelineEdges, runContext);
            List<Map<String, String>> tags = pipelineRunTags(dagId, tenantId, run, trigger, tasks);
            String dagsterRunId = dagster.launch(
                    runConfig.jobName(), "onelake", "onelake-loc",
                    runConfig.opConfig(), tags);
            run.setDagsterRunId(dagsterRunId);
            run.setStatus(DagStatus.RUNNING);
            runRepo.save(run);
            log.info("Spark 流水线 {} 已触发，runId={}，dagsterRunId={}",
                    dagId, run.getId(), dagsterRunId);
        } catch (RuntimeException ex) {
            run.setStatus(DagStatus.FAILED);
            run.setFinishedAt(Instant.now());
            runRepo.save(run);
            // 将所有非终态 task_run 收口为失败。
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

    /** 为前置校验失败的自动重跑补一条 FAILED JobRun，确保剩余次数可沿子运行继续推进。 */
    private void persistRejectedPipelineRetry(JobRun sourceRun,
                                              RunRetryMetadata retryMetadata,
                                              RuntimeException failure) {
        Instant failedAt = Instant.now();
        JobRun rejected = new JobRun();
        rejected.setDagId(sourceRun.getDagId());
        rejected.setDagsterJob(activePipelineDagsterJob(
                sourceRun.getDagId(), sourceRun.getPipelineVersionId()));
        rejected.setTriggerType(TriggerType.AUTO_RETRY);
        rejected.setStatus(DagStatus.FAILED);
        rejected.setLogicalDate(sourceRun.getLogicalDate());
        rejected.setDataIntervalStart(sourceRun.getDataIntervalStart());
        rejected.setDataIntervalEnd(sourceRun.getDataIntervalEnd());
        rejected.setBackfillId(sourceRun.getBackfillId());
        rejected.setPipelineVersionId(sourceRun.getPipelineVersionId());
        rejected.setTimezone(sourceRun.getTimezone());
        rejected.setRunMode(StringUtils.hasText(sourceRun.getRunMode()) ? sourceRun.getRunMode() : "NORMAL");
        rejected.setPriority(sourceRun.getPriority());
        rejected.setRetrySourceRunId(retryMetadata.sourceRunId());
        rejected.setRunRetryAttempt(retryMetadata.attempt());
        rejected.setStartedAt(failedAt);
        rejected.setFinishedAt(failedAt);
        rejected.setTriggeredBy(sourceRun.getTriggeredBy());
        rejected.setTriggeredByName(sourceRun.getTriggeredByName());
        rejected.setUpdatedAt(failedAt);
        runRepo.save(rejected);
        retryMetadata.markRunCreated(rejected.getId());
        log.warn("流水线 {} 自动重跑在建档前校验失败，已记录失败 runId={} sourceRunId={}：{}",
                sourceRun.getDagId(), rejected.getId(), sourceRun.getId(), failure.getMessage());
    }

    /**
     * 对 GRAPH 模式的失败节点执行单节点或下游子图重跑。
     *
     * <p>本次重跑复用原 JobRun，并锁住其全部 TaskRun：累计 attempt 后重置选中子图，
     * 校验子图外依赖，再将 base attempt 传给 Dagster。这样节点回调中的本地重试次数可与
     * 跨 Dagster run 的累计次数保持一致。</p>
     *
     * @param modeRaw {@code SINGLE} 仅重跑目标；{@code DOWNSTREAM} 续跑未成功下游
     */
    @Transactional(noRollbackFor = BizException.class)
    public TaskRerunResult rerunTask(UUID dagId, UUID runId, String taskKey, String modeRaw) {
        if (!isGraphPipelineExecutionMode()) {
            throw new BizException(40062, "从失败续跑仅在 GRAPH 执行模式可用");
        }
        if (!StringUtils.hasText(taskKey)) {
            throw new BizException(40021, "taskKey 不能为空");
        }
        RerunMode mode = RerunMode.parse(modeRaw);
        Dag liveDag = findTenantDag(dagId);
        JobRun run = runRepo.findByIdAndDagIdIn(runId, Set.of(liveDag.getId()))
                .orElseThrow(() -> new BizException(40400, "运行实例不存在"));
        String graphJobName = activePipelineDagsterJob(dagId, run.getPipelineVersionId());
        PipelineSnapshotService.ExecutionSnapshot executionSnapshot = run.getPipelineVersionId() == null
                ? null
                : pipelineSnapshotService.loadExecutionSnapshot(run.getPipelineVersionId(), dagId);
        Dag dag = executionSnapshot == null ? liveDag : executionSnapshot.dag();
        pipelineSnapshotService.activateForDagster(run.getPipelineVersionId());
        dagster.reloadRepositoryLocation("onelake-loc");
        validatePipelineRuntimeContract(dag, graphJobName);

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
        List<PipelineTaskEdge> pipelineEdges = executionSnapshot == null
                ? pipelineTaskEdgeRepo.findByDagId(dagId)
                : new ArrayList<>(executionSnapshot.edges());
        if (pipelineEdges == null) {
            pipelineEdges = List.of();
        }
        // 重跑模式 SINGLE 只重跑目标节点；DOWNSTREAM 沿 PIPELINE 边续跑，但跳过已经成功的下游节点。
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

        List<PipelineTask> allTasks;
        PipelineCompileResult plan;
        if (executionSnapshot == null) {
            plan = pipelineCompileService.compile(dagId);
            allTasks = pipelineTaskRepo.findByDagIdOrderByCreatedAtAsc(dagId);
        } else {
            allTasks = new ArrayList<>(executionSnapshot.tasks());
            plan = pipelineCompileService.compile(
                    dagId, dag.getTenantId(), allTasks, pipelineEdges);
        }
        if (!plan.allValidated()) {
            throw new BizException(40060, "Pipeline 编译未通过，无法重跑: "
                    + String.join("; ",
                    plan.tasks().stream()
                            .filter(t -> !t.valid())
                            .map(t -> t.taskKey() + ": " + t.errorMessage())
                            .toList())
                    + (plan.graphErrors().isEmpty() ? "" : "; " + String.join("; ", plan.graphErrors())));
        }
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
            // 字段 task_run.attempt 保存跨 Dagster run 的累计次数，传给 Dagster 后作为本次本地重试的起点。
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

        // M1 复用同一个 JobRun：先清掉旧 Dagster runId，启动成功后再写入本次新值。
        run.setStatus(DagStatus.RUNNING);
        run.setDagsterRunId(null);
        run.setFinishedAt(null);
        run.setUpdatedAt(now);
        runRepo.save(run);

        TaskBundleContext ctx = new TaskBundleContext(
                dagId, dag.getTenantId(), run.getId(), plan,
                plan.pipelineTag(),
                dag.getResourceGroup() == null ? "spark-default" : dag.getResourceGroup(),
                dag.getComputeProfile() == null ? "spark-small" : dag.getComputeProfile(),
                executionSnapshot == null ? null : executionSnapshot.params(),
                run.getPipelineVersionId()
        );
        RunContext rerunContext = new RunContext(
                run.getLogicalDate(),
                run.getDataIntervalStart(),
                run.getDataIntervalEnd(),
                run.getTimezone(),
                run.getRunMode(),
                run.getBackfillId(),
                run.getTriggerType());
        DagsterRunConfig runConfig = sparkBuilder.buildGraphRunConfig(
                ctx,
                subgraphTasks,
                pipelineEdges,
                pipelineCallbackBaseUrl == null ? "" : pipelineCallbackBaseUrl.trim(),
                Math.max(1, pipelineMaxParallel),
                baseAttempts,
                rerunContext,
                rerunContext.builtInParameters(run.getId()));

        try {
            String dagsterRunId = dagster.launch(
                    runConfig.jobName(), "onelake", "onelake-loc",
                    runConfig.opConfig(), pipelineRerunTags(dag, run, taskKey, mode, subgraphTasks),
                    new ArrayList<>(subgraph));
            run.setDagsterRunId(dagsterRunId);
            run.setStatus(DagStatus.RUNNING);
            run.setUpdatedAt(Instant.now());
            runRepo.save(run);
            log.info("Spark 流水线 {} 已触发节点重跑，runId={}，taskKey={}，mode={}，dagsterRunId={}",
                    dagId, run.getId(), taskKey, mode, dagsterRunId);
            return new TaskRerunResult(runId, new ArrayList<>(subgraph), dagsterRunId);
        } catch (RuntimeException ex) {
            // 启动失败发生在节点已重置之后，需要像首触发失败一样把子图收口到可观测 FAILED。
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
        // GRAPH 模式的每个节点（包括 SYNC_REF）都是 Dagster 的真实 step。
        // 必须由节点回调推进状态，避免预置 SUCCEEDED 使 step key/log 等观测字段被终态保护丢弃。
        if (isGraphPipelineExecutionMode()) {
            Map<String, TaskRunStatus> queued = new LinkedHashMap<>();
            tasks.forEach(task -> queued.put(task.getTaskKey(), TaskRunStatus.QUEUED));
            return queued;
        }
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
                                                    List<PipelineTaskEdge> pipelineEdges,
                                                    RunContext runContext) {
        // GRAPH/LEGACY 只影响 Dagster 作业与 runConfig 形状，C3 仍保持 Java 生成、Dagster 执行。
        Map<String, String> runtimeParams = runContext.builtInParameters(ctx.runId());
        if (isGraphPipelineExecutionMode()) {
            return sparkBuilder.buildGraphRunConfig(
                    ctx,
                    tasks,
                    pipelineEdges,
                    pipelineCallbackBaseUrl == null ? "" : pipelineCallbackBaseUrl.trim(),
                    Math.max(1, pipelineMaxParallel),
                    Map.of(),
                    runContext,
                    runtimeParams);
        }
        return sparkBuilder.build(
                ctx,
                tasks,
                pipelineCallbackBaseUrl == null ? "" : pipelineCallbackBaseUrl.trim(),
                runContext,
                runtimeParams,
                pipelineEdges);
    }

    private List<Map<String, String>> pipelineRunTags(UUID dagId,
                                                      UUID tenantId,
                                                      JobRun run,
                                                      TriggerType trigger,
                                                      List<PipelineTask> tasks) {
        List<Map<String, String>> tags = new ArrayList<>();
        tags.add(Map.of("key", "onelake.pipeline_id", "value", dagId.toString()));
        tags.add(Map.of("key", "onelake.run_id", "value", run.getId().toString()));
        tags.add(Map.of("key", "onelake.tenant_id", "value", tenantId.toString()));
        tags.add(Map.of("key", "onelake.trigger_type", "value", trigger.name()));
        tags.add(Map.of("key", "onelake.engine", "value", pipelineEngineTag(tasks)));
        if (run.getBackfillId() != null) {
            tags.add(Map.of("key", "onelake.backfill_id", "value", run.getBackfillId().toString()));
        }
        if (run.getLogicalDate() != null) {
            tags.add(Map.of("key", "onelake.logical_date", "value", run.getLogicalDate().toString()));
        }
        if (run.getRetrySourceRunId() != null) {
            tags.add(Map.of("key", "onelake.retry_source_run_id", "value", run.getRetrySourceRunId().toString()));
            tags.add(Map.of("key", "onelake.run_retry_attempt", "value", String.valueOf(run.getRunRetryAttempt())));
        }
        return tags;
    }

    private RunContext normalizeRunContext(RunContext context, Dag dag, TriggerType trigger) {
        String defaultTimezone = StringUtils.hasText(dag.getTimezone()) ? dag.getTimezone() : "Asia/Shanghai";
        RunContext normalized = (context == null ? RunContext.empty(trigger) : context)
                .withDefaults(defaultTimezone, trigger)
                .withTriggerType(trigger);
        if (ScheduleMode.from(dag.getScheduleMode()) == ScheduleMode.DRY_RUN) {
            // DAG 配置是权威运行模式；CRON/回填显式传入的 NORMAL 不能覆盖空跑策略。
            normalized = normalized.withRunMode(ScheduleMode.DRY_RUN.name());
        }
        try {
            ZoneId.of(normalized.timezone());
            normalized.validateBusinessTime();
            if (normalized.logicalDate() != null
                    && normalized.dataIntervalStart() == null
                    && normalized.dataIntervalEnd() == null) {
                DataIntervalCalculator.DataInterval interval = dataIntervalCalculator.calculateFromLogicalDate(
                        dag.getPartitionGrain(),
                        normalized.logicalDate(),
                        normalized.timezone());
                normalized = normalized.withDataInterval(
                        interval.dataIntervalStart(),
                        interval.dataIntervalEnd());
            }
            return normalized.validateBusinessTime();
        } catch (IllegalArgumentException ex) {
            throw new BizException(40020, "运行数据日期参数非法: " + ex.getMessage(), ex);
        }
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

    private String activePipelineDagsterJob(UUID dagId, UUID versionId) {
        return isGraphPipelineExecutionMode()
                ? SparkRunConfigBuilder.graphJobName(dagId, versionId)
                : PIPELINE_JOB_NAME;
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
     * 发布 {@code pipeline.run.succeeded} 或 {@code pipeline.run.failed} Outbox 事件。
     *
     * <p>触发失败时由 {@link #triggerPipelineRun} 调用；运行进入终态时由
     * {@code refreshRunStatus} 调用。
     *
     * <p>流水线运行事件用于替代对建模模块运行态表的直接写入。
     */
    public void publishPipelineRunEvent(Dag dag, JobRun run, PipelineCompileResult plan,
                                        boolean succeeded, String errorMessage) {
        OutboxPublisher publisher = outboxPublisher.getIfAvailable();
        if (publisher == null) {
            log.warn("OutboxPublisher 不可用，跳过 pipeline.run.* 事件，runId={}", run.getId());
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
            payload.put("partialSucceeded", List.of()); // 后续由 refreshRunStatus 填充部分成功节点。
        }
        publisher.publish(eventType, dag.getId().toString(), payload);
    }

    /**
     * 刷新回填子运行的 Dagster 状态。
     *
     * <p>回填派发器通过该入口复用正常运行的状态聚合与节点补偿逻辑。</p>
     */
    @Transactional
    public JobRun refreshRunStatusForBackfill(UUID runId) {
        return refreshRunStatusForAutomation(runId);
    }

    /**
     * 后台调度器使用的内部状态同步入口；调用方负责恢复运行所属租户上下文。
     */
    @Transactional
    public JobRun refreshRunStatusForAutomation(UUID runId) {
        JobRun run = runRepo.findById(runId)
                .orElseThrow(() -> new BizException(40400, "运行实例不存在"));
        return refreshRunStatus(run);
    }

    /** 分页查询指定 DAG 的运行，并尽力刷新每条非终态 Dagster 状态。 */
    @Transactional
    public Page<JobRunDTO> runs(UUID dagId, Pageable pageable) {
        Dag dag = findTenantDag(dagId);
        return toRunDTOPage(
                runRepo.findByDagIdOrderByStartedAtDesc(dagId, pageable),
                ignored -> dag);
    }

    /** 分页查询当前租户所有 DAG 的运行，不暴露其他租户的运行记录。 */
    @Transactional
    public Page<JobRunDTO> listRuns(Pageable pageable) {
        List<Dag> dags = dagRepo.findByTenantId(TenantContext.getTenantId());
        if (dags.isEmpty()) {
            return Page.empty(pageable);
        }
        Map<UUID, Dag> dagById = dags.stream()
            .collect(Collectors.toMap(Dag::getId, Function.identity()));
        return toRunDTOPage(
                runRepo.findByDagIdInOrderByStartedAtDesc(dagById.keySet(), pageable),
                run -> dagById.get(run.getDagId()));
    }

    /** 按 logical date 升序分页查询某次回填产生的子运行。 */
    @Transactional
    public Page<JobRunDTO> listBackfillRuns(UUID dagId, UUID backfillId, Pageable pageable) {
        Dag dag = findTenantDag(dagId);
        return toRunDTOPage(
                runRepo.findByDagIdAndBackfillIdOrderByLogicalDateAsc(dagId, backfillId, pageable),
                ignored -> dag);
    }

    /** 查询并刷新指定回填子运行，同时校验 DAG、回填和运行三者的归属关系。 */
    @Transactional
    public JobRunDTO getBackfillRun(UUID dagId, UUID backfillId, UUID runId) {
        Dag dag = findTenantDag(dagId);
        JobRun run = runRepo.findByIdAndDagIdAndBackfillId(runId, dagId, backfillId)
                .orElseThrow(() -> new BizException(40400, "回填子运行不存在"));
        return toRunDTO(refreshRunStatus(run), dag);
    }

    /** 查询当前租户可见的单条运行，并同步其最新 Dagster 状态。 */
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

    /**
     * 取消运行并收口所有未终态节点。
     *
     * <p>Dagster terminate 是尽力而为；即使远端终止调用失败，本地运行仍进入 CANCELLED，
     * 避免调度和回填派发继续把它当作活动运行。</p>
     */
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
                log.warn("Dagster 终止运行 {} 失败：{}；本地仍标记为已取消",
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
        if (request.outputs() != null && !request.outputs().isObject()) {
            throw new BizException(40025, "task_run outputs 必须是 JSON 对象");
        }
        if (request.outputs() != null && requested != TaskRunStatus.SUCCEEDED) {
            throw new BizException(40025, "只有 SUCCEEDED 回调可以写入 task_run outputs");
        }
        callbackRowsWritten(request);
        callbackArtifactPath(request);

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

    /**
     * 在 Dagster 节点实际执行前，用同一 JobRun 内已经成功的上游 outputs 最终渲染配置。
     *
     * <p>runConfig 初次构建时保留 {@code ${upstream.*}} 占位符并冻结祖先 taskKey；原生
     * Dagster 图保证当前节点只会在依赖 step 终态后开始，因此这里读到的是回调已提交的
     * 输出快照，且不受运行期间流水线拓扑编辑影响。</p>
     */
    @Transactional(readOnly = true)
    public TaskConfigRenderResult renderTaskConfig(UUID runId,
                                                   String taskKey,
                                                   JsonNode config,
                                                   List<String> frozenUpstreamTaskKeys) {
        if (!StringUtils.hasText(taskKey)) {
            throw new BizException(40021, "taskKey 不能为空");
        }
        if (config == null || config.isNull()) {
            throw new BizException(40025, "待渲染节点 config 不能为空");
        }
        runRepo.findById(runId)
                .orElseThrow(() -> new BizException(40400, "运行实例不存在"));
        List<TaskRun> taskRuns = taskRunRepo.findByJobRunId(runId);
        if (taskRuns.stream().noneMatch(taskRun -> taskKey.equals(taskRun.getTaskKey()))) {
            throw new BizException(40400, "task_run 不存在: " + taskKey);
        }

        try {
            Set<String> referencedTaskKeys = upstreamTaskKeys(config);
            Set<String> ancestorTaskKeys = new LinkedHashSet<>(
                    frozenUpstreamTaskKeys == null ? List.of() : frozenUpstreamTaskKeys);
            for (String referencedTaskKey : referencedTaskKeys) {
                if (!ancestorTaskKeys.contains(referencedTaskKey)) {
                    throw new IllegalArgumentException(
                            "引用的节点 " + referencedTaskKey + " 不是当前节点的图上游");
                }
            }

            Map<String, ParamRenderer.UpstreamTaskOutput> upstreamOutputs = new LinkedHashMap<>();
            for (TaskRun taskRun : taskRuns) {
                if (!referencedTaskKeys.contains(taskRun.getTaskKey())) {
                    continue;
                }
                upstreamOutputs.put(taskRun.getTaskKey(), new ParamRenderer.UpstreamTaskOutput(
                        taskRun.getStatus().name(),
                        taskRun.getStatus() == TaskRunStatus.SUCCEEDED
                                ? parseTaskRunOutputs(taskRun)
                                : JsonUtil.mapper().createObjectNode()));
            }
            return new TaskConfigRenderResult(renderTaskConfigNode(config.deepCopy(), upstreamOutputs));
        } catch (IllegalArgumentException ex) {
            throw new BizException(40025,
                    "节点 " + taskKey + " 参数渲染失败: " + ex.getMessage(), ex);
        }
    }

    private Set<String> upstreamTaskKeys(JsonNode node) {
        Set<String> taskKeys = new LinkedHashSet<>();
        if (node == null || node.isNull()) {
            return taskKeys;
        }
        if (node.isTextual()) {
            taskKeys.addAll(ParamRenderer.upstreamTaskKeys(node.asText()));
        } else if (node.isContainerNode()) {
            node.forEach(child -> taskKeys.addAll(upstreamTaskKeys(child)));
        }
        return taskKeys;
    }

    /**
     * 读取租户内节点日志，支持完整流式下载或服务端截取末尾若干行。
     *
     * <p>{@code logRef} 必须位于 {@code tenantId/runId/} 前缀下，且禁止绝对路径和目录穿越。</p>
     *
     * @param tailLines {@code null} 或 0 返回完整日志；正数返回末尾行，最大 10000
     */
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
                log.warn("流水线节点运行状态同步失败 {}：{}", run.getDagsterRunId(), e.getMessage());
            }
            return run;
        }
        try {
            // 远端 run 状态是聚合状态的主来源；GRAPH 模式同时用节点回调补偿观测延迟。
            DagsterClient.RunStatus status = dagster.getRunStatus(run.getDagsterRunId());
            DagStatus mapped = mapDagsterStatus(status.status());
            run.setStatus(mapped);
            if (status.startedAt() != null) {
                run.setStartedAt(status.startedAt());
            }
            if (isTerminal(mapped)) {
                run.setFinishedAt(status.finishedAt() == null ? Instant.now() : status.finishedAt());
            } else {
                DagStatus reconciled = reconcileFinishedGraphRunFromTaskRuns(run, mapped);
                if (reconciled != null) {
                    mapped = reconciled;
                    run.setStatus(reconciled);
                }
            }
            runRepo.save(run);

            // V2 流水线首次进入终态时发布 pipeline.run.succeeded/failed。
            if (isTerminal(mapped) && !isTerminal(oldStatus)) {
                DagStatus reconciled = syncTaskRunsFromTerminalRun(run, mapped);
                if (reconciled != mapped) {
                    run.setStatus(reconciled);
                    runRepo.save(run);
                }
                publishPipelineRunEventsIfTerminal(run, reconciled);
            }
        } catch (RuntimeException e) {
            log.warn("刷新 Dagster run 状态失败 {}：{}", run.getDagsterRunId(), e.getMessage());
        }
        return run;
    }

    private DagStatus reconcileFinishedGraphRunFromTaskRuns(JobRun run, DagStatus dagsterStatus) {
        if (!isGraphPipelineExecutionMode()
                || isTerminal(dagsterStatus)
                || run.getFinishedAt() == null) {
            return null;
        }
        // 修复历史 GRAPH 行：Dagster 偶发仍报 STARTED，但本地 run/task_run 已全部带终态时间。
        List<TaskRun> taskRuns = taskRunRepo.findByJobRunIdForUpdate(run.getId());
        if (taskRuns.isEmpty()) {
            return null;
        }
        for (TaskRun tr : taskRuns) {
            if (!isTerminalTaskRunStatus(tr.getStatus())) {
                return null;
            }
        }
        DagStatus reconciled = reconcileGraphRunStatusFromTaskRuns(taskRuns, DagStatus.SUCCEEDED);
        log.warn("Dagster graph run {} 仍为 {}，但本地 task_run 已全部终态；将 run {} 校正为 {}",
                run.getDagsterRunId(), dagsterStatus, run.getId(), reconciled);
        return reconciled;
    }

    /**
     * 终态刷新：LEGACY 模式下 Dagster 把 Spark 流水线作为一个固定 op 运行。
     * 在节点级 Spark 产物可解析之前，所有非终态 task_run 跟随 Dagster run 的终态，
     * 避免 UI 和 Outbox 长期停留在 QUEUED。
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
        List<PipelineTaskEdge> allEdges = run.getPipelineVersionId() == null
                ? pipelineTaskEdgeRepo.findByDagId(run.getDagId())
                : pipelineSnapshotService
                        .loadExecutionSnapshot(run.getPipelineVersionId(), run.getDagId())
                        .edges();
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
        Long rowsWritten = callbackRowsWritten(request);
        String artifactPath = callbackArtifactPath(request);
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
        if (rowsWritten != null) {
            taskRun.setRowsWritten(rowsWritten);
        }
        if (request.scanBytes() != null) {
            taskRun.setScanBytes(request.scanBytes());
        }
        if (artifactPath != null) {
            taskRun.setArtifactPath(artifactPath);
        }
        if (requested == TaskRunStatus.SUCCEEDED) {
            ObjectNode outputs = request.outputs() != null
                    ? (ObjectNode) request.outputs().deepCopy()
                    : JsonUtil.mapper().createObjectNode();
            if (rowsWritten != null) {
                outputs.put("rowsWritten", rowsWritten);
            }
            if (artifactPath != null) {
                outputs.put("artifactPath", artifactPath);
            }
            taskRun.setOutputs(JsonUtil.toJson(outputs));
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

    private Long callbackRowsWritten(TaskRunCallbackRequest request) {
        Long typedValue = request.rowsWritten();
        if (typedValue != null && typedValue < 0) {
            throw new BizException(40025, "rowsWritten 必须大于等于 0");
        }
        JsonNode outputValue = request.outputs() == null
                ? null : request.outputs().get("rowsWritten");
        if (outputValue == null || outputValue.isNull()) {
            return typedValue;
        }
        if (!outputValue.isIntegralNumber()
                || !outputValue.canConvertToLong()
                || outputValue.longValue() < 0) {
            throw new BizException(40025, "outputs.rowsWritten 必须是非负 long 整数");
        }
        long normalized = outputValue.longValue();
        if (typedValue != null && typedValue.longValue() != normalized) {
            throw new BizException(40025, "rowsWritten 与 outputs.rowsWritten 不一致");
        }
        return normalized;
    }

    private String callbackArtifactPath(TaskRunCallbackRequest request) {
        String typedValue = request.artifactPath();
        if (typedValue != null && typedValue.length() > 512) {
            throw new BizException(40025, "artifactPath 长度不能超过 512");
        }
        JsonNode outputValue = request.outputs() == null
                ? null : request.outputs().get("artifactPath");
        if (outputValue == null || outputValue.isNull()) {
            return typedValue;
        }
        if (!outputValue.isTextual() || outputValue.asText().length() > 512) {
            throw new BizException(40025, "outputs.artifactPath 必须是长度不超过 512 的字符串");
        }
        String normalized = outputValue.asText();
        if (typedValue != null && !typedValue.equals(normalized)) {
            throw new BizException(40025, "artifactPath 与 outputs.artifactPath 不一致");
        }
        return normalized;
    }

    private JsonNode parseTaskRunOutputs(TaskRun taskRun) {
        if (!StringUtils.hasText(taskRun.getOutputs())) {
            return JsonUtil.mapper().createObjectNode();
        }
        try {
            JsonNode outputs = JsonUtil.mapper().readTree(taskRun.getOutputs());
            if (!outputs.isObject()) {
                throw new IllegalArgumentException("task_run.outputs 不是 JSON 对象");
            }
            return outputs;
        } catch (Exception ex) {
            throw new IllegalArgumentException(
                    "上游节点 " + taskRun.getTaskKey() + " 的 outputs JSON 非法", ex);
        }
    }

    private JsonNode renderTaskConfigNode(
            JsonNode node,
            Map<String, ParamRenderer.UpstreamTaskOutput> upstreamOutputs) {
        if (node.isTextual()) {
            return JsonUtil.mapper().getNodeFactory().textNode(
                    ParamRenderer.render(node.asText(), null, Map.of(), upstreamOutputs));
        }
        if (node.isObject()) {
            ObjectNode rendered = (ObjectNode) node;
            List<String> fields = new ArrayList<>();
            rendered.fieldNames().forEachRemaining(fields::add);
            fields.forEach(field -> rendered.set(
                    field, renderTaskConfigNode(rendered.get(field), upstreamOutputs)));
            return rendered;
        }
        if (node.isArray()) {
            ArrayNode rendered = (ArrayNode) node;
            for (int index = 0; index < rendered.size(); index++) {
                rendered.set(index, renderTaskConfigNode(rendered.get(index), upstreamOutputs));
            }
            return rendered;
        }
        return node;
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
            log.warn("节点运行摘要跳过 row_count 查询，fqn={}：{}", fqn, e.getMessage());
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
     * V2 流水线首次到达终态时发布 {@code pipeline.run.succeeded/failed}，
     * 并按成功节点补发 {@code pipeline.task.loaded}。
     *
     */
    private void publishPipelineRunEventsIfTerminal(JobRun run, DagStatus terminalStatus) {
        try {
            Dag dag = dagRepo.findById(run.getDagId()).orElse(null);
            if (dag == null) return;
            // 只处理 V2 流水线作业。
            if (!PIPELINE_JOB_NAME.equals(dag.getDagsterJob())
                    && !PIPELINE_GRAPH_JOB_NAME.equals(dag.getDagsterJob())) return;

            boolean succeeded = terminalStatus == DagStatus.SUCCEEDED;
            // 1. 为本次作业中的可执行成功节点发布 pipeline.task.loaded。
            List<TaskRun> taskRuns = taskRunRepo.findByJobRunId(run.getId());
            for (TaskRun tr : taskRuns) {
                publishPipelineTaskLoadedEvent(dag, run, tr, succeeded);
            }
            // 2. 发布聚合级 pipeline.run.succeeded/failed。
            publishPipelineRunEvent(dag, run, null, succeeded,
                    pipelineRunTerminalErrorMessage(terminalStatus));
            log.info("流水线 {} 运行 {} 已发布终态事件：{}",
                    dag.getId(), run.getId(), succeeded ? "SUCCEEDED" : "FAILED");
        } catch (RuntimeException e) {
            log.warn("发布运行 {} 的终态事件失败：{}",
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
        // 仅对 SUCCEEDED 节点发布 task.loaded；失败节点通过 pipeline.run.failed 暴露。
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
            log.warn("流水线节点 {} 的 config 解析失败，已跳过：{}", task.getTaskKey(), e.getMessage());
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

    private Page<JobRunDTO> toRunDTOPage(Page<JobRun> page, Function<JobRun, Dag> dagResolver) {
        List<JobRun> refreshed = page.getContent().stream()
                .map(this::refreshRunStatus)
                .toList();
        Set<UUID> versionIds = refreshed.stream()
                .map(JobRun::getPipelineVersionId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, Integer> versionNumbers = pipelineSnapshotService.versionNumbers(versionIds);
        List<JobRunDTO> content = refreshed.stream()
                .map(run -> toRunDTO(run, dagResolver.apply(run),
                        run.getPipelineVersionId() == null
                                ? null
                                : versionNumbers.get(run.getPipelineVersionId())))
                .toList();
        return new PageImpl<>(content, page.getPageable(), page.getTotalElements());
    }

    private JobRunDTO toRunDTO(JobRun r, Dag dag) {
        Map<UUID, Integer> versionNumbers = pipelineSnapshotService.versionNumbers(
                r.getPipelineVersionId() == null ? Set.of() : Set.of(r.getPipelineVersionId()));
        return toRunDTO(r, dag, r.getPipelineVersionId() == null
                ? null
                : versionNumbers.get(r.getPipelineVersionId()));
    }

    private JobRunDTO toRunDTO(JobRun r, Dag dag, Integer pipelineVersion) {
        return new JobRunDTO(r.getId(), r.getDagId(),
            dag == null ? null : dag.getName(),
            StringUtils.hasText(r.getDagsterJob()) ? r.getDagsterJob() : (dag == null ? null : dag.getDagsterJob()),
            r.getDagsterRunId(),
            r.getTriggerType().name(), r.getStatus().name(),
            r.getRunMode(),
            StringUtils.hasText(r.getTimezone())
                    ? r.getTimezone()
                    : (dag == null || !StringUtils.hasText(dag.getTimezone()) ? "Asia/Shanghai" : dag.getTimezone()),
            r.getLogicalDate(), r.getDataIntervalStart(), r.getDataIntervalEnd(), r.getBackfillId(),
            r.getStartedAt(), r.getFinishedAt(), r.getTriggeredBy(), displayTriggerActor(r),
            r.getSlaMissed(), r.getRetrySourceRunId(), r.getRunRetryAttempt(),
            r.getPipelineVersionId(), pipelineVersion);
    }

    private record TriggerReadiness(boolean triggerable, String reason) {}

    private static final class RunRetryMetadata {
        private final UUID sourceRunId;
        private final int attempt;
        private UUID createdRunId;

        private RunRetryMetadata(UUID sourceRunId, int attempt) {
            this.sourceRunId = sourceRunId;
            this.attempt = attempt;
        }

        UUID sourceRunId() {
            return sourceRunId;
        }

        int attempt() {
            return attempt;
        }

        void markRunCreated(UUID runId) {
            this.createdRunId = runId;
        }

        boolean runCreated() {
            return createdRunId != null;
        }
    }

    private record TaskRunSummary(Long rowsWritten, String artifactPath) {}

    /**
     * 节点日志下载资源。
     *
     * @param objectKey 对象存储内部键，不作为客户端文件路径使用
     * @param filename 面向下载响应的文件名
     * @param contentLength 当前返回内容的字节数
     * @param content 日志输入流，由 HTTP 响应写出方负责关闭
     */
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
