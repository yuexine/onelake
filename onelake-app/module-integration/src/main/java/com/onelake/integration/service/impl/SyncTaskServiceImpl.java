package com.onelake.integration.service.impl;

import com.onelake.common.audit.AuditLogger;
import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.outbox.DomainEvents;
import com.onelake.common.outbox.OutboxPublisher;
import com.onelake.integration.api.vo.CreateSyncTaskVO;
import com.onelake.integration.api.vo.UpdateSyncTaskVO;
import com.onelake.integration.domain.entity.DataSource;
import com.onelake.integration.client.AirbyteSyncDriver;
import com.onelake.integration.domain.entity.SyncRun;
import com.onelake.integration.domain.entity.SyncTask;
import com.onelake.integration.domain.enums.RunStatus;
import com.onelake.integration.domain.enums.SyncMode;
import com.onelake.integration.domain.enums.TaskStatus;
import com.onelake.integration.dto.SyncRunDTO;
import com.onelake.integration.dto.SyncTaskDTO;
import com.onelake.integration.mapper.SyncTaskMapper;
import com.onelake.integration.repository.DataSourceRepository;
import com.onelake.integration.repository.SyncRunRepository;
import com.onelake.integration.repository.SyncTaskRepository;
import com.onelake.integration.service.SyncTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
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
    private final AuditLogger audit;
    private final OutboxPublisher outbox;

    @Override
    @Transactional
    public SyncTaskDTO create(CreateSyncTaskVO vo) {
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new BizException(40100, "租户上下文缺失");
        }
        dsRepo.findById(vo.sourceId())
            .orElseThrow(() -> new BizException(40400, "数据源不存在"));
        if (taskRepo.existsByTenantIdAndName(tenantId, vo.name())) {
            throw new BizException(40911, "采集任务名称已存在");
        }
        SyncTask task = mapper.toEntity(vo);
        task.setTenantId(tenantId);
        task.setStatus(TaskStatus.DRAFT);
        taskRepo.save(task);
        audit.auditCreate("sync_task", task.getId(), null);
        outbox.publish(DomainEvents.INTEGRATION_SYNC_TASK_CREATED, task.getId().toString(),
            java.util.Map.of(
                "name", task.getName(),
                "sourceId", task.getSourceId().toString(),
                "mode", String.valueOf(task.getMode()),
                "targetTable", task.getTargetTable() == null ? "" : task.getTargetTable(),
                "tenantId", tenantId.toString()
            ));
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
        task.setStatus(TaskStatus.ENABLED);
        audit.audit("ENABLE", "sync_task", id.toString(), null);
        outbox.publish(DomainEvents.INTEGRATION_SYNC_TASK_STATUS_CHANGED, id.toString(),
            java.util.Map.of("status", "ENABLED", "name", task.getName()));
        return toDTO(task);
    }

    @Override
    @Transactional
    public SyncTaskDTO disable(UUID id) {
        SyncTask task = taskRepo.findById(id)
            .orElseThrow(() -> new BizException(40400, "同步任务不存在"));
        task.setStatus(TaskStatus.PAUSED);
        audit.audit("DISABLE", "sync_task", id.toString(), null);
        outbox.publish(DomainEvents.INTEGRATION_SYNC_TASK_STATUS_CHANGED, id.toString(),
            java.util.Map.of("status", "PAUSED", "name", task.getName()));
        return toDTO(task);
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
        long jobId = airbyte.triggerSync(task.getAirbyteConnectionId());

        SyncRun run = new SyncRun();
        run.setTaskId(taskId);
        run.setExternalJobId(String.valueOf(jobId));
        run.setStatus(RunStatus.RUNNING);
        run.setStartedAt(Instant.now());
        runRepo.save(run);

        audit.audit("TRIGGER", "sync_task", taskId.toString(),
            java.util.Map.of("runId", run.getId().toString(), "externalJobId", jobId));
        outbox.publish(DomainEvents.INTEGRATION_SYNC_RUN_STARTED, run.getId().toString(),
            java.util.Map.of(
                "taskId", taskId.toString(),
                "externalJobId", String.valueOf(jobId),
                "targetTable", task.getTargetTable(),
                "tenantId", task.getTenantId().toString()
            ));
        return run.getId();
    }

    @Override
    @Transactional
    public void reconcile(UUID runId) {
        SyncRun run = runRepo.findById(runId)
            .orElseThrow(() -> new BizException(40400, "运行实例不存在"));
        if (run.getExternalJobId() == null) return;
        RunStatus previousStatus = run.getStatus();
        try {
            String s = airbyte.getJobStatus(Long.parseLong(run.getExternalJobId()));
            run.setStatus(RunStatus.valueOf(s.toUpperCase()));
            if (run.getStatus() != RunStatus.RUNNING) {
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
                // 加载 task 以获取 targetTable + tenantId，供下游消费方定位资产
                SyncTask taskForEvent = taskRepo.findById(run.getTaskId()).orElse(null);
                String targetTable = taskForEvent == null ? "" : taskForEvent.getTargetTable();
                UUID tenantId = taskForEvent == null ? null : taskForEvent.getTenantId();
                java.util.Map<String, Object> payload = new java.util.HashMap<>();
                payload.put("runId", run.getId().toString());
                payload.put("taskId", run.getTaskId().toString());
                payload.put("externalJobId", run.getExternalJobId() == null ? "" : run.getExternalJobId());
                payload.put("status", String.valueOf(run.getStatus()));
                payload.put("targetTable", targetTable == null ? "" : targetTable);
                payload.put("namespace", namespaceOf(targetTable));
                payload.put("table", tableNameOf(targetTable));
                payload.put("rowsRead", run.getRowsRead() == null ? 0L : run.getRowsRead());
                payload.put("rowsSynced", run.getRowsWritten() == null ? 0L : run.getRowsWritten());
                if (taskForEvent != null) payload.put("sourceId", taskForEvent.getSourceId().toString());
                if (tenantId != null) payload.put("tenantId", tenantId.toString());
                outbox.publish(eventName, run.getId().toString(), payload);
            }
        } catch (Exception e) {
            log.warn("reconcile run {} failed: {}", runId, e.getMessage());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Page<SyncRunDTO> runs(UUID taskId, Pageable pageable) {
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

    private Long throughputRows(SyncRun run) {
        Long durationMs = durationMs(run);
        if (durationMs == null || durationMs <= 0 || run.getRowsWritten() == null) return null;
        return run.getRowsWritten() * 1000 / durationMs;
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
}
