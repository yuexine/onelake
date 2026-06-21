package com.onelake.integration.service;

import com.onelake.integration.api.vo.CreateSyncTaskVO;
import com.onelake.integration.api.vo.UpdateSyncTaskVO;
import com.onelake.integration.dto.SyncRunLogDTO;
import com.onelake.integration.dto.SyncRunDTO;
import com.onelake.integration.dto.SyncTaskDTO;
import com.onelake.integration.dto.SyncTaskDryRunDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

public interface SyncTaskService {

    SyncTaskDTO create(CreateSyncTaskVO vo);

    SyncTaskDTO get(UUID id);

    List<SyncTaskDTO> list(UUID sourceId, String mode, String status, String keyword);

    List<SyncTaskDTO> listBySource(UUID sourceId);

    SyncTaskDTO update(UUID id, UpdateSyncTaskVO vo);

    void delete(UUID id);

    SyncTaskDTO enable(UUID id);

    SyncTaskDTO disable(UUID id);

    SyncTaskDryRunDTO dryRun(CreateSyncTaskVO vo);

    SyncTaskDryRunDTO dryRun(UUID taskId);

    UUID trigger(UUID taskId);

    SyncRunDTO getRun(UUID runId);

    List<SyncRunLogDTO> logs(UUID runId);

    SyncRunDTO cancelRun(UUID runId);

    /** 由 orchestration 的定时器/Sensor 调用，回写最终状态。 */
    void reconcile(UUID runId);

    Page<SyncRunDTO> runs(UUID taskId, Pageable pageable);
}
