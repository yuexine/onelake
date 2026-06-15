package com.onelake.integration.service.impl;

import com.onelake.common.audit.AuditLogger;
import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.integration.api.vo.CreateSyncTaskVO;
import com.onelake.integration.client.AirbyteSyncDriver;
import com.onelake.integration.domain.entity.SyncRun;
import com.onelake.integration.domain.entity.SyncTask;
import com.onelake.integration.domain.enums.RunStatus;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
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

    @Override
    @Transactional
    public SyncTaskDTO create(CreateSyncTaskVO vo) {
        dsRepo.findById(vo.sourceId())
            .orElseThrow(() -> new BizException(40400, "数据源不存在"));
        SyncTask task = mapper.toEntity(vo);
        task.setTenantId(TenantContext.getTenantId());
        task.setStatus(TaskStatus.DRAFT);
        taskRepo.save(task);
        audit.auditCreate("sync_task", task.getId(), null);
        return mapper.toDTO(task);
    }

    @Override
    @Transactional(readOnly = true)
    public SyncTaskDTO get(UUID id) {
        return mapper.toDTO(taskRepo.findById(id)
            .orElseThrow(() -> new BizException(40400, "同步任务不存在")));
    }

    @Override
    @Transactional(readOnly = true)
    public List<SyncTaskDTO> listBySource(UUID sourceId) {
        return taskRepo.findBySourceId(sourceId).stream().map(mapper::toDTO).toList();
    }

    @Override
    @Transactional
    public UUID trigger(UUID taskId) {
        SyncTask task = taskRepo.findById(taskId)
            .orElseThrow(() -> new BizException(40400, "同步任务不存在"));
        if (task.getStatus() != TaskStatus.ENABLED) {
            throw new BizException(40010, "任务未启用");
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
        return run.getId();
    }

    @Override
    @Transactional
    public void reconcile(UUID runId) {
        SyncRun run = runRepo.findById(runId)
            .orElseThrow(() -> new BizException(40400, "运行实例不存在"));
        if (run.getExternalJobId() == null) return;
        try {
            String s = airbyte.getJobStatus(Long.parseLong(run.getExternalJobId()));
            run.setStatus(RunStatus.valueOf(s.toUpperCase()));
            if (run.getStatus() != RunStatus.RUNNING) {
                run.setFinishedAt(Instant.now());
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
                r.getErrorCode(), r.getStartedAt(), r.getFinishedAt()));
    }
}
