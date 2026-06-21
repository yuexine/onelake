package com.onelake.integration.service.impl;

import com.onelake.common.audit.AuditLogger;
import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.exception.DataplaneException;
import com.onelake.common.outbox.DomainEvents;
import com.onelake.common.outbox.OutboxPublisher;
import com.onelake.integration.api.vo.CreateSyncTaskVO;
import com.onelake.integration.api.vo.UpdateSyncTaskVO;
import com.onelake.integration.domain.entity.DataSource;
import com.onelake.integration.client.AirbyteSyncDriver;
import com.onelake.integration.client.DagsterScheduleClient;
import com.onelake.integration.domain.entity.SyncRun;
import com.onelake.integration.domain.entity.SyncTask;
import com.onelake.integration.domain.enums.RunStatus;
import com.onelake.integration.domain.enums.SyncMode;
import com.onelake.integration.domain.enums.TaskStatus;
import com.onelake.integration.dto.SyncRunLogDTO;
import com.onelake.integration.dto.SyncRunDTO;
import com.onelake.integration.dto.SyncTaskDTO;
import com.onelake.integration.dto.SyncTaskDryRunDTO;
import com.onelake.integration.mapper.SyncTaskMapper;
import com.onelake.integration.repository.DataSourceRepository;
import com.onelake.integration.repository.SyncRunRepository;
import com.onelake.integration.repository.SyncTaskRepository;
import com.onelake.integration.service.SyncTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.core.type.TypeReference;
import com.onelake.common.util.JsonUtil;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 同步任务服务（对应《技术初始化文档》§6.11 建任务 → 触发 Airbyte → 回写 sync_run）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SyncTaskServiceImpl implements SyncTaskService {

    private final SyncTaskRepository taskRepo;
    private final SyncRunRepository runRepo;
    private final DataSourceRepository dsRepo;
    private final SyncTaskMapper mapper;
    private final AirbyteSyncDriver airbyte;
    private final DagsterScheduleClient dagsterSchedule;
    private final AuditLogger audit;
    private final OutboxPublisher outbox;

    @Value("${onelake.dataplane.airbyte.destination-id:}")
    private String defaultAirbyteDestinationId;

    @Value("${onelake.dataplane.airbyte.workspace-id:}")
    private String defaultAirbyteWorkspaceId;

    @Value("${onelake.dataplane.airbyte.source-definitions.mysql:}")
    private String defaultMySqlSourceDefinitionId;

    @Value("${onelake.dataplane.airbyte.source-definitions.postgres:}")
    private String defaultPostgresSourceDefinitionId;

    @Value("${onelake.dataplane.airbyte.destination-definition-id:}")
    private String defaultAirbyteDestinationDefinitionId;

    @Value("${onelake.dataplane.airbyte.destination-config:{}}")
    private String defaultAirbyteDestinationConfig;

    @Override
    @Transactional
    public SyncTaskDTO create(CreateSyncTaskVO vo) {
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new BizException(40100, "租户上下文缺失");
        }
        DataSource source = dsRepo.findById(vo.sourceId())
            .orElseThrow(() -> new BizException(40400, "数据源不存在"));
        if (!tenantId.equals(source.getTenantId())) {
            throw new BizException(40400, "数据源不存在");
        }
        if (taskRepo.existsByTenantIdAndName(tenantId, vo.name())) {
            throw new BizException(40911, "采集任务名称已存在");
        }
        SyncTask task = mapper.toEntity(vo);
        task.setTenantId(tenantId);
        task.setStatus(TaskStatus.DRAFT);
        taskRepo.save(task);
        audit.auditCreate("sync_task", task.getId(), null);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", task.getId().toString());
        payload.put("name", task.getName());
        payload.put("sourceId", task.getSourceId().toString());
        payload.put("mode", String.valueOf(task.getMode()));
        payload.put("sourceTable", task.getSourceTable() == null ? "" : task.getSourceTable());
        payload.put("targetTable", task.getTargetTable() == null ? "" : task.getTargetTable());
        payload.put("fieldMapping", fieldMappingPayload(task));
        payload.put("tenantId", tenantId.toString());
        outbox.publish(DomainEvents.INTEGRATION_SYNC_TASK_CREATED, task.getId().toString(), payload);
        return toDTO(task);
    }

    @Override
    @Transactional(readOnly = true)
    public SyncTaskDTO get(UUID id) {
        return toDTO(taskRepo.findById(id)
            .orElseThrow(() -> new BizException(40400, "同步任务不存在")));
    }

    @Override
    @Transactional(readOnly = true)
    public List<SyncTaskDTO> list(UUID sourceId, String mode, String status, String keyword) {
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new BizException(40100, "租户上下文缺失");
        }
        Specification<SyncTask> spec = (root, query, cb) -> cb.equal(root.get("tenantId"), tenantId);
        if (sourceId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("sourceId"), sourceId));
        }
        if (mode != null && !mode.isBlank()) {
            SyncMode parsedMode = parseMode(mode);
            spec = spec.and((root, query, cb) -> cb.equal(root.get("mode"), parsedMode));
        }
        if (status != null && !status.isBlank()) {
            TaskStatus parsedStatus = parseStatus(status);
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), parsedStatus));
        }
        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.toLowerCase() + "%";
            spec = spec.and((root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("name")), like),
                cb.like(cb.lower(root.get("targetTable")), like)
            ));
        }
        return taskRepo.findAll(spec).stream().map(this::toDTO).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<SyncTaskDTO> listBySource(UUID sourceId) {
        return taskRepo.findBySourceId(sourceId).stream().map(this::toDTO).toList();
    }

    @Override
    @Transactional
    public SyncTaskDTO update(UUID id, UpdateSyncTaskVO vo) {
        SyncTask task = taskRepo.findById(id)
            .orElseThrow(() -> new BizException(40400, "同步任务不存在"));
        if (task.getStatus() == TaskStatus.ENABLED) {
            throw new BizException(40013, "已启用任务请先暂停再编辑");
        }
        if (vo.name() != null && !vo.name().isBlank()) task.setName(vo.name());
        if (vo.mode() != null && !vo.mode().isBlank()) task.setMode(parseMode(vo.mode()));
        if (vo.sourceTable() != null && !vo.sourceTable().isBlank()) task.setSourceTable(vo.sourceTable());
        if (vo.targetTable() != null && !vo.targetTable().isBlank()) task.setTargetTable(vo.targetTable());
        if (vo.fieldMapping() != null) task.setFieldMapping(com.onelake.common.util.JsonUtil.toJson(vo.fieldMapping()));
        if (vo.scheduleCron() != null) task.setScheduleCron(vo.scheduleCron());
        if (vo.rateLimit() != null) task.setRateLimit(vo.rateLimit());
        if (vo.dirtyThreshold() != null) task.setDirtyThreshold(vo.dirtyThreshold());
        if (vo.airbyteConnectionId() != null) task.setAirbyteConnectionId(vo.airbyteConnectionId());
        audit.auditUpdate("sync_task", id, Map.of("fields", "patched"));
        return toDTO(task);
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        SyncTask task = taskRepo.findById(id)
            .orElseThrow(() -> new BizException(40400, "同步任务不存在"));
        if (task.getStatus() == TaskStatus.ENABLED) {
            throw new BizException(40014, "已启用任务不能删除，请先暂停");
        }
        if (runRepo.existsByTaskIdAndStatus(id, RunStatus.RUNNING)) {
            throw new BizException(40912, "任务存在运行中实例，不能删除");
        }
        taskRepo.delete(task);
        audit.auditDelete("sync_task", id);
    }

    @Override
    @Transactional
    public SyncTaskDTO enable(UUID id) {
        SyncTask task = taskRepo.findById(id)
            .orElseThrow(() -> new BizException(40400, "同步任务不存在"));
        if (task.getAirbyteConnectionId() == null || task.getAirbyteConnectionId().isBlank()) {
            task.setAirbyteConnectionId(ensureAirbyteConnection(task));
        }
        task.setStatus(TaskStatus.ENABLED);
        boolean scheduleRegistered = dagsterSchedule.registerOrUpdate(task);
        audit.audit("ENABLE", "sync_task", id.toString(),
            Map.of("dagsterScheduleRegistered", scheduleRegistered));
        outbox.publish(DomainEvents.INTEGRATION_SYNC_TASK_STATUS_CHANGED, id.toString(),
            java.util.Map.of("status", "ENABLED", "name", task.getName(), "scheduleRegistered", scheduleRegistered));
        return toDTO(task);
    }

    @Override
    @Transactional
    public SyncTaskDTO disable(UUID id) {
        SyncTask task = taskRepo.findById(id)
            .orElseThrow(() -> new BizException(40400, "同步任务不存在"));
        task.setStatus(TaskStatus.PAUSED);
        boolean scheduleDisabled = dagsterSchedule.disable(task);
        audit.audit("DISABLE", "sync_task", id.toString(),
            Map.of("dagsterScheduleDisabled", scheduleDisabled));
        outbox.publish(DomainEvents.INTEGRATION_SYNC_TASK_STATUS_CHANGED, id.toString(),
            java.util.Map.of("status", "PAUSED", "name", task.getName(), "scheduleDisabled", scheduleDisabled));
        return toDTO(task);
    }

    @Override
    @Transactional(readOnly = true)
    public SyncTaskDryRunDTO dryRun(CreateSyncTaskVO vo) {
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new BizException(40100, "租户上下文缺失");
        }
        DataSource source = dsRepo.findById(vo.sourceId())
            .orElseThrow(() -> new BizException(40400, "数据源不存在"));
        if (!tenantId.equals(source.getTenantId())) {
            throw new BizException(40400, "数据源不存在");
        }
        return buildDryRunReport(source, vo.sourceTable(), vo.targetTable(), vo.fieldMapping(), vo.airbyteConnectionId());
    }

    @Override
    @Transactional(readOnly = true)
    public SyncTaskDryRunDTO dryRun(UUID taskId) {
        SyncTask task = taskRepo.findById(taskId)
            .orElseThrow(() -> new BizException(40400, "同步任务不存在"));
        DataSource source = dsRepo.findById(task.getSourceId())
            .orElseThrow(() -> new BizException(40400, "数据源不存在"));
        return buildDryRunReport(source, task.getSourceTable(), task.getTargetTable(), task.getFieldMapping(), task.getAirbyteConnectionId());
    }

    @Override
    @Transactional
    public UUID trigger(UUID taskId) {
        SyncTask task = taskRepo.findById(taskId)
            .orElseThrow(() -> new BizException(40400, "同步任务不存在"));
        if (task.getStatus() != TaskStatus.ENABLED) {
            throw new BizException(40010, "任务未启用");
        }
        if (task.getAirbyteConnectionId() == null || task.getAirbyteConnectionId().isBlank()) {
            throw new BizException(40015, "任务未绑定 Airbyte Connection，暂不能触发");
        }

        SyncRun run = new SyncRun();
        run.setTaskId(taskId);
        run.setStatus(RunStatus.QUEUED);
        run.setStartedAt(Instant.now());
        runRepo.save(run);

        try {
            long jobId = airbyte.triggerSync(task.getAirbyteConnectionId());
            run.setExternalJobId(String.valueOf(jobId));
            run.setStatus(RunStatus.RUNNING);
            runRepo.save(run);

            audit.audit("TRIGGER", "sync_task", taskId.toString(),
                java.util.Map.of("runId", run.getId().toString(), "externalJobId", jobId));
            outbox.publish(DomainEvents.INTEGRATION_SYNC_RUN_STARTED, run.getId().toString(),
                runEventPayload(task, taskId, jobId));
        } catch (RuntimeException e) {
            run.setStatus(RunStatus.FAILED);
            run.setErrorCode("AIRBYTE_TRIGGER_FAILED");
            run.setErrorMsg(e.getMessage());
            run.setFinishedAt(Instant.now());
            runRepo.save(run);
            java.util.Map<String, Object> payload = runEventPayload(task, taskId, null);
            payload.put("runId", run.getId().toString());
            payload.put("errorMsg", e.getMessage() == null ? "" : e.getMessage());
            outbox.publish(DomainEvents.INTEGRATION_SYNC_FAILED, run.getId().toString(), payload);
            throw e;
        }
        return run.getId();
    }

    private java.util.Map<String, Object> runEventPayload(SyncTask task, UUID taskId, Long jobId) {
        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("taskId", taskId.toString());
        payload.put("externalJobId", jobId == null ? "" : String.valueOf(jobId));
        payload.put("sourceTable", task.getSourceTable() == null ? "" : task.getSourceTable());
        payload.put("targetTable", task.getTargetTable() == null ? "" : task.getTargetTable());
        payload.put("tenantId", task.getTenantId() == null ? "" : task.getTenantId().toString());
        return payload;
    }

    @Override
    @Transactional
    public SyncRunDTO getRun(UUID runId) {
        SyncRun run = runRepo.findById(runId)
            .orElseThrow(() -> new BizException(40400, "运行实例不存在"));
        refreshActiveRun(run);
        return toRunDTO(run);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SyncRunLogDTO> logs(UUID runId) {
        SyncRun run = runRepo.findById(runId)
            .orElseThrow(() -> new BizException(40400, "运行实例不存在"));
        if (run.getExternalJobId() == null || run.getExternalJobId().isBlank()) {
            return localRunLogs(run);
        }
        try {
            long jobId = Long.parseLong(run.getExternalJobId());
            List<String> lines = airbyte.getJobLogs(jobId);
            Instant timestamp = run.getFinishedAt() == null ? Instant.now() : run.getFinishedAt();
            return lines.stream()
                .map(line -> new SyncRunLogDTO(timestamp, line.toLowerCase().contains("error") ? "ERROR" : "INFO", line))
                .toList();
        } catch (Exception e) {
            return List.of(new SyncRunLogDTO(Instant.now(), "WARN", "Airbyte 日志读取失败: " + e.getMessage()));
        }
    }

    @Override
    @Transactional
    public SyncRunDTO cancelRun(UUID runId) {
        SyncRun run = runRepo.findById(runId)
            .orElseThrow(() -> new BizException(40400, "运行实例不存在"));
        if (run.getStatus() != RunStatus.RUNNING && run.getStatus() != RunStatus.QUEUED) {
            return toRunDTO(run);
        }
        boolean cancelled = false;
        if (run.getExternalJobId() != null && !run.getExternalJobId().isBlank()) {
            cancelled = airbyte.cancel(Long.parseLong(run.getExternalJobId()));
        }
        run.setStatus(cancelled || run.getExternalJobId() == null ? RunStatus.CANCELLED : RunStatus.FAILED);
        if (!cancelled && run.getExternalJobId() != null) {
            run.setErrorCode("AIRBYTE_CANCEL_FAILED");
            run.setErrorMsg("Airbyte job 取消失败，请稍后刷新状态或在 Airbyte 控制台确认。");
        }
        run.setFinishedAt(Instant.now());
        runRepo.save(run);
        audit.audit("CANCEL", "sync_run", runId.toString(), Map.of("externalJobId", run.getExternalJobId() == null ? "" : run.getExternalJobId()));
        return toRunDTO(run);
    }

    @Override
    @Transactional
    public void reconcile(UUID runId) {
        SyncRun run = runRepo.findById(runId)
            .orElseThrow(() -> new BizException(40400, "运行实例不存在"));
        refreshRunSnapshot(run);
    }

    private void refreshActiveRun(SyncRun run) {
        if (run.getStatus() == RunStatus.QUEUED || run.getStatus() == RunStatus.RUNNING) {
            refreshRunSnapshot(run);
        }
    }

    private void refreshRunSnapshot(SyncRun run) {
        if (run.getExternalJobId() == null) return;
        RunStatus previousStatus = run.getStatus();
        try {
            long jobId = Long.parseLong(run.getExternalJobId());
            AirbyteSyncDriver.AirbyteJobSnapshot snapshot = airbyte.getJobSnapshot(jobId);
            RunStatus nextStatus = mapAirbyteStatus(snapshot.status());
            run.setStatus(nextStatus);
            if (snapshot.recordsSynced() != null) {
                run.setRowsRead(snapshot.recordsSynced());
                run.setRowsWritten(snapshot.recordsSynced());
            }
            if (snapshot.errorMessage() != null && !snapshot.errorMessage().isBlank()) {
                run.setErrorCode("AIRBYTE_JOB_FAILED");
                run.setErrorMsg(snapshot.errorMessage());
            }
            run.setCheckpoint(JsonUtil.toJson(snapshot.checkpoint(jobId)));
            if (isTerminal(run.getStatus()) && run.getFinishedAt() == null) {
                run.setFinishedAt(Instant.now());
            }
            runRepo.save(run);

            // 状态从未终态转为终态时发事件（catalog / orchestration / security 消费）
            boolean becameTerminal =
                (previousStatus == RunStatus.QUEUED || previousStatus == RunStatus.RUNNING)
                && (run.getStatus() == RunStatus.SUCCEEDED || run.getStatus() == RunStatus.FAILED);
            if (becameTerminal) {
                String eventName = run.getStatus() == RunStatus.SUCCEEDED
                    ? DomainEvents.INTEGRATION_TABLE_LOADED
                    : DomainEvents.INTEGRATION_SYNC_FAILED;
                // 加载 task 以获取 sourceTable/targetTable + tenantId，供下游消费方定位资产
                SyncTask taskForEvent = taskRepo.findById(run.getTaskId()).orElse(null);
                String sourceTable = taskForEvent == null ? "" : taskForEvent.getSourceTable();
                String targetTable = taskForEvent == null ? "" : taskForEvent.getTargetTable();
                UUID tenantId = taskForEvent == null ? null : taskForEvent.getTenantId();
                java.util.Map<String, Object> payload = new java.util.HashMap<>();
                payload.put("runId", run.getId().toString());
                payload.put("taskId", run.getTaskId().toString());
                payload.put("externalJobId", run.getExternalJobId() == null ? "" : run.getExternalJobId());
                payload.put("status", String.valueOf(run.getStatus()));
                payload.put("sourceTable", sourceTable == null ? "" : sourceTable);
                payload.put("targetTable", targetTable == null ? "" : targetTable);
                payload.put("namespace", namespaceOf(targetTable));
                payload.put("table", tableNameOf(targetTable));
                payload.put("rowsRead", run.getRowsRead() == null ? 0L : run.getRowsRead());
                payload.put("rowsSynced", run.getRowsWritten() == null ? 0L : run.getRowsWritten());
                payload.put("fieldMapping", fieldMappingPayload(taskForEvent));
                if (taskForEvent != null) payload.put("sourceId", taskForEvent.getSourceId().toString());
                if (tenantId != null) payload.put("tenantId", tenantId.toString());
                outbox.publish(eventName, run.getId().toString(), payload);
            }
        } catch (Exception e) {
            log.warn("reconcile run {} failed: {}", run.getId(), e.getMessage());
        }
    }

    @Override
    @Transactional
    public Page<SyncRunDTO> runs(UUID taskId, Pageable pageable) {
        List<SyncRun> activeRuns = runRepo.findByTaskIdAndStatusIn(taskId, List.of(RunStatus.QUEUED, RunStatus.RUNNING));
        if (activeRuns != null) {
            activeRuns.forEach(this::refreshActiveRun);
        }
        return runRepo.findByTaskIdOrderByStartedAtDesc(taskId, pageable)
            .map(r -> new SyncRunDTO(
                r.getId(), r.getTaskId(), r.getExternalJobId(),
                r.getStatus().name(), r.getRowsRead(), r.getRowsWritten(),
                r.getErrorCode(), r.getErrorMsg(), r.getCheckpoint(),
                durationMs(r), throughputRows(r), r.getStartedAt(), r.getFinishedAt()));
    }

    private SyncTaskDTO toDTO(SyncTask task) {
        String sourceName = dsRepo.findById(task.getSourceId())
            .map(DataSource::getName)
            .orElse(null);
        return mapper.toDTO(task, sourceName);
    }

    private SyncRunDTO toRunDTO(SyncRun r) {
        return new SyncRunDTO(
            r.getId(), r.getTaskId(), r.getExternalJobId(),
            r.getStatus().name(), r.getRowsRead(), r.getRowsWritten(),
            r.getErrorCode(), r.getErrorMsg(), r.getCheckpoint(),
            durationMs(r), throughputRows(r), r.getStartedAt(), r.getFinishedAt());
    }

    private SyncMode parseMode(String mode) {
        try {
            return SyncMode.valueOf(mode.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BizException(40016, "不支持的采集模式: " + mode);
        }
    }

    private TaskStatus parseStatus(String status) {
        try {
            return TaskStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BizException(40017, "不支持的任务状态: " + status);
        }
    }

    private Long durationMs(SyncRun run) {
        if (run.getStartedAt() == null || run.getFinishedAt() == null) return null;
        return java.time.Duration.between(run.getStartedAt(), run.getFinishedAt()).toMillis();
    }

    private Double throughputRows(SyncRun run) {
        Long durationMs = durationMs(run);
        if (durationMs == null || durationMs <= 0 || run.getRowsWritten() == null) return null;
        return run.getRowsWritten() * 1000.0 / durationMs;
    }

    private String ensureAirbyteConnection(SyncTask task) {
        DataSource source = dsRepo.findById(task.getSourceId())
            .orElseThrow(() -> new BizException(40400, "数据源不存在"));
        if (!task.getTenantId().equals(source.getTenantId())) {
            throw new BizException(40400, "数据源不存在");
        }
        Map<String, Object> config = configMap(source);
        String workspaceId = firstText(config, "airbyteWorkspaceId", "workspaceId");
        if (workspaceId.isBlank()) {
            workspaceId = defaultAirbyteWorkspaceId == null ? "" : defaultAirbyteWorkspaceId.trim();
        }
        String airbyteSourceId = firstText(config, "airbyteSourceId", "externalSourceId");
        String airbyteDestinationId = firstText(config, "airbyteDestinationId", "externalDestinationId");
        boolean configChanged = false;
        if (airbyteSourceId.isBlank()) {
            String sourceDefinitionId = firstText(config, "airbyteSourceDefinitionId", "sourceDefinitionId");
            if (sourceDefinitionId.isBlank()) {
                sourceDefinitionId = defaultSourceDefinitionId(source.getType());
            }
            if (sourceDefinitionId.isBlank()) {
                throw new BizException(40032, "数据源未配置 airbyteSourceId 或 sourceDefinitionId，无法发布采集任务");
            }
            if (workspaceId.isBlank()) {
                throw new BizException(40034, "未配置 Airbyte workspaceId，无法创建 Source");
            }
            try {
                airbyteSourceId = airbyte.ensureSource(
                    "",
                    workspaceId,
                    sourceDefinitionId,
                    "onelake-" + source.getName(),
                    airbyteSourceConfig(source, config)
                );
                config.put("airbyteSourceId", airbyteSourceId);
                configChanged = true;
            } catch (DataplaneException e) {
                throw new BizException(50010, e.getMessage());
            }
        }
        if (airbyteDestinationId.isBlank()) {
            airbyteDestinationId = defaultAirbyteDestinationId == null ? "" : defaultAirbyteDestinationId.trim();
        }
        if (airbyteDestinationId.isBlank()) {
            String destinationDefinitionId = firstText(config, "airbyteDestinationDefinitionId", "destinationDefinitionId");
            if (destinationDefinitionId.isBlank()) {
                destinationDefinitionId = defaultAirbyteDestinationDefinitionId == null ? "" : defaultAirbyteDestinationDefinitionId.trim();
            }
            if (destinationDefinitionId.isBlank()) {
                throw new BizException(40033, "未配置 Airbyte destinationId 或 destinationDefinitionId，无法发布采集任务");
            }
            if (workspaceId.isBlank()) {
                throw new BizException(40034, "未配置 Airbyte workspaceId，无法创建 Destination");
            }
            try {
                airbyteDestinationId = airbyte.ensureDestination(
                    "",
                    workspaceId,
                    destinationDefinitionId,
                    "onelake-lakehouse",
                    airbyteDestinationConfig(config)
                );
                config.put("airbyteDestinationId", airbyteDestinationId);
                configChanged = true;
            } catch (DataplaneException e) {
                throw new BizException(50010, e.getMessage());
            }
        }
        if (configChanged) {
            source.setConfig(JsonUtil.toJson(config));
            dsRepo.save(source);
        }
        try {
        return airbyte.ensureConnection(
            airbyteSourceId,
            airbyteDestinationId,
            "onelake-" + task.getName(),
            task.getSourceTable(),
            task.getTargetTable(),
            fieldMappingList(task.getFieldMapping())
        );
        } catch (DataplaneException e) {
            throw new BizException(50010, e.getMessage());
        }
    }

    private Map<String, Object> configMap(DataSource source) {
        try {
            Map<String, Object> parsed = JsonUtil.mapper().readValue(source.getConfig(), new TypeReference<Map<String, Object>>() {});
            return new LinkedHashMap<>(parsed);
        } catch (Exception e) {
            throw new BizException(50001, "数据源配置解析失败");
        }
    }

    private SyncTaskDryRunDTO buildDryRunReport(DataSource source, String sourceTable, String targetTable, Object fieldMapping, String connectionId) {
        Map<String, Object> config = configMap(source);
        List<SyncTaskDryRunDTO.Check> checks = new ArrayList<>();
        checks.add(check("source", "数据源", source.getId() != null, "数据源可读取"));
        checks.add(check("source_table", "来源表", sourceTable != null && sourceTable.contains("."), "来源表需包含 schema.table"));
        checks.add(check("target_table", "目标表", targetTable != null && targetTable.contains("."), "目标表需包含 schema.table"));
        checks.add(check("field_mapping", "字段映射", hasFieldMapping(fieldMapping), "字段映射已生成"));

        String explicitConnectionId = connectionId == null || connectionId.isBlank()
            ? firstText(config, "airbyteConnectionId", "externalConnectionId")
            : connectionId.trim();
        if (!explicitConnectionId.isBlank()) {
            try {
                boolean ok = airbyte.checkConnection(explicitConnectionId);
                checks.add(check("airbyte_connection", "Airbyte Connection", ok, ok ? "Connection 可访问" : "Connection 不存在或不可访问"));
            } catch (Exception e) {
                checks.add(check("airbyte_connection", "Airbyte Connection", false, "Connection 检查失败: " + e.getMessage()));
            }
        } else {
            String workspaceId = firstText(config, "airbyteWorkspaceId", "workspaceId");
            if (workspaceId.isBlank()) workspaceId = defaultAirbyteWorkspaceId == null ? "" : defaultAirbyteWorkspaceId.trim();
            String sourceId = firstText(config, "airbyteSourceId", "externalSourceId");
            String sourceDefinitionId = firstText(config, "airbyteSourceDefinitionId", "sourceDefinitionId");
            if (sourceDefinitionId.isBlank()) sourceDefinitionId = defaultSourceDefinitionId(source.getType());
            String destinationId = firstText(config, "airbyteDestinationId", "externalDestinationId");
            if (destinationId.isBlank()) destinationId = defaultAirbyteDestinationId == null ? "" : defaultAirbyteDestinationId.trim();
            String destinationDefinitionId = firstText(config, "airbyteDestinationDefinitionId", "destinationDefinitionId");
            if (destinationDefinitionId.isBlank()) destinationDefinitionId = defaultAirbyteDestinationDefinitionId == null ? "" : defaultAirbyteDestinationDefinitionId.trim();
            boolean workspaceRequired = sourceId.isBlank() || destinationId.isBlank();

            checks.add(check("airbyte_workspace", "Airbyte Workspace", !workspaceRequired || !workspaceId.isBlank(),
                workspaceRequired ? "需要 workspaceId 才能动态创建管道" : "已有 source/destination，可复用现有资源"));
            checks.add(check("airbyte_source", "Airbyte Source", !sourceId.isBlank() || !sourceDefinitionId.isBlank(), "已有 sourceId 或可创建 source"));
            checks.add(check("airbyte_destination", "Airbyte Destination", !destinationId.isBlank() || !destinationDefinitionId.isBlank(), "已有 destinationId 或可创建 destination"));
        }
        boolean ready = checks.stream().allMatch(SyncTaskDryRunDTO.Check::passed);
        return new SyncTaskDryRunDTO(ready, checks);
    }

    private SyncTaskDryRunDTO.Check check(String code, String label, boolean passed, String message) {
        return new SyncTaskDryRunDTO.Check(code, label, passed, message);
    }

    private boolean hasFieldMapping(Object fieldMapping) {
        if (fieldMapping == null) {
            return false;
        }
        if (fieldMapping instanceof String text) {
            return !text.isBlank() && !"[]".equals(text.trim());
        }
        if (fieldMapping instanceof List<?> list) {
            return !list.isEmpty();
        }
        return true;
    }

    private List<Map<String, Object>> fieldMappingList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return JsonUtil.mapper().readValue(
                json,
                JsonUtil.mapper().getTypeFactory().constructCollectionType(List.class, Map.class)
            );
        } catch (Exception e) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> airbyteSourceConfig(DataSource source, Map<String, Object> config) {
        Object raw = config.get("airbyteSourceConfig");
        if (raw instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        copyIfPresent(config, result, "host", "host");
        copyIfPresent(config, result, "port", "port");
        copyIfPresent(config, result, "username", "username");
        copyIfPresent(config, result, "password", "password");
        copyIfPresent(config, result, "database", "database");
        if (!result.containsKey("database")) {
            copyIfPresent(config, result, "dbName", "database");
        }
        copyIfPresent(config, result, "schema", "schema");
        result.putIfAbsent("ssl", false);
        result.putIfAbsent("replication_method", Map.of("method", source.getType() == com.onelake.integration.domain.enums.DataSourceType.POSTGRES ? "Standard" : "STANDARD"));
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> airbyteDestinationConfig(Map<String, Object> config) {
        Object raw = config.get("airbyteDestinationConfig");
        if (raw instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        if (defaultAirbyteDestinationConfig != null && !defaultAirbyteDestinationConfig.isBlank()) {
            try {
                return JsonUtil.mapper().readValue(defaultAirbyteDestinationConfig, new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                throw new BizException(50001, "Airbyte 默认 Destination 配置解析失败");
            }
        }
        return Map.of();
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String sourceKey, String targetKey) {
        Object value = source.get(sourceKey);
        if (value != null && !String.valueOf(value).isBlank()) {
            target.put(targetKey, value);
        }
    }

    private String defaultSourceDefinitionId(com.onelake.integration.domain.enums.DataSourceType type) {
        if (type == com.onelake.integration.domain.enums.DataSourceType.MYSQL) {
            return defaultMySqlSourceDefinitionId == null ? "" : defaultMySqlSourceDefinitionId.trim();
        }
        if (type == com.onelake.integration.domain.enums.DataSourceType.POSTGRES) {
            return defaultPostgresSourceDefinitionId == null ? "" : defaultPostgresSourceDefinitionId.trim();
        }
        return "";
    }

    private RunStatus mapAirbyteStatus(String status) {
        if (status == null) {
            return RunStatus.RUNNING;
        }
        return switch (status.toLowerCase()) {
            case "pending", "queued" -> RunStatus.QUEUED;
            case "running" -> RunStatus.RUNNING;
            case "succeeded", "success" -> RunStatus.SUCCEEDED;
            case "failed", "error", "incomplete" -> RunStatus.FAILED;
            case "cancelled", "canceled" -> RunStatus.CANCELLED;
            default -> RunStatus.RUNNING;
        };
    }

    private boolean isTerminal(RunStatus status) {
        return status == RunStatus.SUCCEEDED || status == RunStatus.FAILED || status == RunStatus.CANCELLED;
    }

    private List<SyncRunLogDTO> localRunLogs(SyncRun run) {
        List<SyncRunLogDTO> lines = new ArrayList<>();
        lines.add(new SyncRunLogDTO(run.getStartedAt(), "INFO", "OneLake 已创建本地运行实例，等待数据面 job id。"));
        if (run.getErrorMsg() != null && !run.getErrorMsg().isBlank()) {
            lines.add(new SyncRunLogDTO(run.getFinishedAt() == null ? Instant.now() : run.getFinishedAt(), "ERROR", run.getErrorMsg()));
        }
        return lines;
    }

    private String firstText(Map<String, Object> config, String first, String second) {
        Object firstValue = config.get(first);
        if (firstValue != null && !String.valueOf(firstValue).isBlank()) {
            return String.valueOf(firstValue).trim();
        }
        Object secondValue = config.get(second);
        return secondValue == null ? "" : String.valueOf(secondValue).trim();
    }

    private String namespaceOf(String targetTable) {
        if (targetTable == null || targetTable.isBlank() || !targetTable.contains(".")) {
            return "";
        }
        return targetTable.substring(0, targetTable.lastIndexOf('.'));
    }

    private String tableNameOf(String targetTable) {
        if (targetTable == null || targetTable.isBlank()) {
            return "";
        }
        int dot = targetTable.lastIndexOf('.');
        return dot >= 0 ? targetTable.substring(dot + 1) : targetTable;
    }

    private Object fieldMappingPayload(SyncTask task) {
        if (task == null) {
            return List.of();
        }
        return fieldMappingList(task.getFieldMapping());
    }
}
