package com.onelake.integration.service;

import com.onelake.integration.api.vo.CreateSyncTaskVO;
import com.onelake.integration.dto.SyncRunDTO;
import com.onelake.integration.dto.SyncTaskDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

public interface SyncTaskService {

    SyncTaskDTO create(CreateSyncTaskVO vo);

    SyncTaskDTO get(UUID id);

    List<SyncTaskDTO> listBySource(UUID sourceId);

    UUID trigger(UUID taskId);

    /** 由 orchestration 的定时器/Sensor 调用，回写最终状态。 */
    void reconcile(UUID runId);

    Page<SyncRunDTO> runs(UUID taskId, Pageable pageable);
}
