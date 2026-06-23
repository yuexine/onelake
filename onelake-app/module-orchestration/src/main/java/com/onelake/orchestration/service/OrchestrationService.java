package com.onelake.orchestration.service;

import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.modeling.DwdModelRunSynchronizer;
import com.onelake.common.util.JsonUtil;
import com.onelake.orchestration.client.DagsterClient;
import com.onelake.orchestration.domain.entity.Dag;
import com.onelake.orchestration.domain.entity.JobRun;
import com.onelake.orchestration.domain.enums.DagStatus;
import com.onelake.orchestration.domain.enums.TriggerType;
import com.onelake.orchestration.dto.DagDTO;
import com.onelake.orchestration.dto.JobRunDTO;
import com.onelake.orchestration.repository.DagRepository;
import com.onelake.orchestration.repository.JobRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrchestrationService {

    private static final String DRAFT_DAGSTER_JOB = "sql_workbench_draft";

    private final DagRepository dagRepo;
    private final JobRunRepository runRepo;
    private final DagsterClient dagster;
    private final JdbcTemplate jdbc;
    private final ObjectProvider<DwdModelRunSynchronizer> dwdModelRunSynchronizer;
    private final RuntimeContractService runtimeContractService;

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
        runRepo.save(run);

        DwdRunContext dwdRun = null;
        try {
            LaunchSpec launchSpec = launchSpec(dag, run, trigger);
            dwdRun = launchSpec.dwdRun();
            String dagsterRunId = launchSpec.runConfig() == null
                ? dagster.launch(dag.getDagsterJob(), "onelake", "onelake-loc")
                : dagster.launch(dag.getDagsterJob(), "onelake", "onelake-loc",
                    launchSpec.runConfig(), launchSpec.tags());
            run.setDagsterRunId(dagsterRunId);
            run.setStatus(DagStatus.RUNNING);
            runRepo.save(run);
            if (dwdRun != null) {
                updateDwdModelRunLaunched(dwdRun.modelRunId(), dagsterRunId);
            }
        } catch (RuntimeException ex) {
            run.setStatus(DagStatus.FAILED);
            run.setFinishedAt(Instant.now());
            runRepo.save(run);
            if (dwdRun != null) {
                updateDwdModelRunFailed(dwdRun.modelRunId(), ex.getMessage());
            }
            if (ex instanceof BizException bizException) {
                throw bizException;
            }
            throw new BizException(50200, "Dagster 触发失败: " + ex.getMessage());
        }
        return run.getId();
    }

    private LaunchSpec launchSpec(Dag dag, JobRun run, TriggerType trigger) {
        @SuppressWarnings("unchecked")
        Map<String, Object> definition = dag.getDefinition() == null || dag.getDefinition().isBlank()
            ? Map.of()
            : JsonUtil.fromJson(dag.getDefinition(), Map.class);
        if (!"DWD_MODEL_DAG".equals(asString(definition.get("kind")))) {
            return new LaunchSpec(null, null, null);
        }
        DwdRunContext dwdRun = createDwdModelRun(dag, run, trigger, definition);
        return new LaunchSpec(dwdRun.runConfig(), dwdRun.tags(), dwdRun);
    }

    private DwdRunContext createDwdModelRun(Dag dag, JobRun orchestrationRun, TriggerType trigger,
                                           Map<String, Object> definition) {
        UUID tenantId = TenantContext.getTenantId();
        UUID modelId = parseUuid(asString(definition.get("modelId")), "DWD DAG definition 缺少 modelId");
        Map<String, Object> model = jdbc.queryForMap("""
            SELECT id, tenant_id, status, dbt_model_name, source_fqn, target_fqn,
                   artifact_path, resource_group, compute_profile
            FROM modeling.data_model
            WHERE id = ? AND tenant_id = ?
            """, modelId, tenantId);
        String status = asString(model.get("status"));
        if (!"VALIDATED".equalsIgnoreCase(status)) {
            throw new BizException(40055, "DWD 模型必须先完成 compile/validate 后才能运行");
        }
        String dbtModelName = firstText(asString(model.get("dbt_model_name")), asString(definition.get("dbtModelName")));
        if (!StringUtils.hasText(dbtModelName)) {
            throw new BizException(40056, "DWD 模型缺少 dbtModelName");
        }
        String artifactPath = firstText(asString(model.get("artifact_path")), asString(definition.get("artifactPath")));
        if (!StringUtils.hasText(artifactPath)) {
            throw new BizException(40057, "DWD 模型缺少 dbt 产物路径，请先重新 compile");
        }

        UUID modelRunId = UUID.randomUUID();
        String triggerType = trigger == TriggerType.EVENT ? "ODS_EVENT" : "MANUAL";
        String resourceGroup = firstText(asString(model.get("resource_group")), asString(definition.get("resourceGroup")));
        String computeProfile = firstText(asString(model.get("compute_profile")), asString(definition.get("computeProfile")));
        jdbc.update("""
            INSERT INTO modeling.model_run
                (id, tenant_id, model_id, status, trigger_type, orchestration_dag_id,
                 resource_group, compute_profile, artifacts_path, queued_at, created_at, updated_at)
            VALUES (?, ?, ?, 'QUEUED', ?, ?, ?, ?, ?, now(), now(), now())
            """, modelRunId, tenantId, modelId, triggerType, dag.getId(),
            resourceGroup, computeProfile, artifactPath);
        jdbc.update("""
            UPDATE modeling.data_model
            SET last_run_id = ?, updated_at = now()
            WHERE id = ? AND tenant_id = ?
            """, modelRunId, modelId, tenantId);

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("model_name", dbtModelName);
        config.put("model_id", modelId.toString());
        config.put("run_id", modelRunId.toString());
        config.put("tenant_id", tenantId.toString());
        config.put("trigger_type", triggerType);
        config.put("source_fqn", firstText(asString(model.get("source_fqn")), asString(definition.get("sourceFqn"))));
        config.put("target_fqn", firstText(asString(model.get("target_fqn")), asString(definition.get("targetFqn"))));
        config.put("artifact_path", artifactPath);
        config.put("resource_group", firstText(resourceGroup, ""));
        config.put("compute_profile", firstText(computeProfile, ""));
        config.put("backfill", Map.of(
            "enabled", false,
            "fullRefresh", false,
            "partitionStart", "",
            "partitionEnd", "",
            "sourceIntegrationRunId", ""
        ));
        Map<String, Object> runConfig = Map.of("ops", Map.of("run_dwd_model", Map.of("config", config)));
        List<Map<String, String>> tags = List.of(
            Map.of("key", "onelake.model_id", "value", modelId.toString()),
            Map.of("key", "onelake.model_run_id", "value", modelRunId.toString()),
            Map.of("key", "onelake.orchestration_run_id", "value", orchestrationRun.getId().toString()),
            Map.of("key", "onelake.tenant_id", "value", tenantId.toString()),
            Map.of("key", "onelake.trigger_type", "value", triggerType),
            Map.of("key", "onelake.dbt_model", "value", dbtModelName)
        );
        return new DwdRunContext(modelRunId, runConfig, tags);
    }

    private void updateDwdModelRunLaunched(UUID modelRunId, String dagsterRunId) {
        jdbc.update("""
            UPDATE modeling.model_run
            SET dagster_run_id = ?, status = 'RUNNING', started_at = COALESCE(started_at, now()), updated_at = now()
            WHERE id = ?
            """, dagsterRunId, modelRunId);
    }

    private void updateDwdModelRunFailed(UUID modelRunId, String errorMessage) {
        jdbc.update("""
            UPDATE modeling.model_run
            SET status = 'FAILED',
                started_at = COALESCE(started_at, now()),
                finished_at = now(),
                error_msg = ?,
                updated_at = now()
            WHERE id = ?
            """, truncate(errorMessage, 2000), modelRunId);
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
        if (isTerminal(run.getStatus())) {
            try {
                syncDwdModelRunStatus(run.getDagsterRunId(), run.getStatus(), run.getStartedAt(), run.getFinishedAt());
            } catch (RuntimeException e) {
                log.warn("DWD model run status sync failed for {}: {}", run.getDagsterRunId(), e.getMessage());
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
            syncDwdModelRunStatus(run.getDagsterRunId(), mapped, status.startedAt(), run.getFinishedAt());
        } catch (RuntimeException e) {
            log.warn("Dagster run status refresh failed for {}: {}", run.getDagsterRunId(), e.getMessage());
        }
        return run;
    }

    private void syncDwdModelRunStatus(String dagsterRunId, DagStatus status, Instant startedAt, Instant finishedAt) {
        if (!StringUtils.hasText(dagsterRunId)) {
            return;
        }
        if (refreshDwdModelRunThroughModeling(dagsterRunId)) {
            return;
        }
        String modelStatus = switch (status) {
            case SUCCESS -> "SUCCEEDED";
            case FAILED -> "FAILED";
            case QUEUED -> "QUEUED";
            case RUNNING -> "RUNNING";
        };
        if (isTerminal(status)) {
            jdbc.update("""
                UPDATE modeling.model_run
                SET status = ?,
                    started_at = COALESCE(started_at, CAST(? AS timestamp with time zone)),
                    finished_at = COALESCE(finished_at, CAST(? AS timestamp with time zone)),
                    updated_at = now()
                WHERE dagster_run_id = ?
                """, modelStatus, toTimestamp(startedAt), toTimestamp(finishedAt), dagsterRunId);
        } else {
            jdbc.update("""
                UPDATE modeling.model_run
                SET status = ?,
                    started_at = COALESCE(started_at, CAST(? AS timestamp with time zone)),
                    updated_at = now()
                WHERE dagster_run_id = ?
                """, modelStatus, toTimestamp(startedAt), dagsterRunId);
        }
    }

    private boolean refreshDwdModelRunThroughModeling(String dagsterRunId) {
        DwdModelRunSynchronizer synchronizer = dwdModelRunSynchronizer.getIfAvailable();
        if (synchronizer == null) {
            return false;
        }
        try {
            return synchronizer.refreshByDagsterRunId(dagsterRunId);
        } catch (RuntimeException e) {
            log.warn("DWD model run synchronizer failed for {}: {}", dagsterRunId, e.getMessage());
            return false;
        }
    }

    private Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private DagStatus mapDagsterStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return DagStatus.RUNNING;
        }
        return switch (status.trim().toUpperCase()) {
            case "SUCCESS", "SUCCEEDED" -> DagStatus.SUCCESS;
            case "FAILURE", "FAILED", "CANCELED", "CANCELLED" -> DagStatus.FAILED;
            case "QUEUED", "NOT_STARTED" -> DagStatus.QUEUED;
            default -> DagStatus.RUNNING;
        };
    }

    private boolean isTerminal(DagStatus status) {
        return status == DagStatus.SUCCESS || status == DagStatus.FAILED;
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

    private UUID parseUuid(String value, String errorMessage) {
        if (!StringUtils.hasText(value)) {
            throw new BizException(40022, errorMessage);
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new BizException(40023, "DWD DAG definition 中的 modelId 非法");
        }
    }

    private String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String firstText(String first, String fallback) {
        return StringUtils.hasText(first) ? first : fallback;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private JobRunDTO toRunDTO(JobRun r, Dag dag) {
        return new JobRunDTO(r.getId(), r.getDagId(),
            dag == null ? null : dag.getName(),
            dag == null ? null : dag.getDagsterJob(),
            r.getDagsterRunId(),
            r.getTriggerType().name(), r.getStatus().name(),
            r.getStartedAt(), r.getFinishedAt(), r.getTriggeredBy());
    }

    private record TriggerReadiness(boolean triggerable, String reason) {}

    private record LaunchSpec(
        Map<String, Object> runConfig,
        List<Map<String, String>> tags,
        DwdRunContext dwdRun
    ) {}

    private record DwdRunContext(
        UUID modelRunId,
        Map<String, Object> runConfig,
        List<Map<String, String>> tags
    ) {}
}
