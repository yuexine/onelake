package com.onelake.integration.service;

import com.onelake.integration.domain.entity.CdcTask;
import com.onelake.integration.dto.CdcStatusDTO;

import java.util.List;
import java.util.UUID;

/**
 * CDC 实时采集任务服务。
 *
 * <p>核心能力：
 * <ul>
 *   <li>创建 / 列表 / 删除 CDC 任务</li>
 *   <li>启动 / 暂停（当前 stub —— 真实实现需 FlinkCDC API）</li>
 *   <li>查询位点 / 延迟</li>
 * </ul>
 */
public interface CdcTaskService {

    CdcTask create(UUID sourceId, String tableName);

    List<CdcTask> list();

    CdcTask get(UUID id);

    void delete(UUID id);

    CdcTask start(UUID id);

    CdcTask pause(UUID id);

    /** 返回当前位点 + 延迟（stub — 真实需 Flink JobManager REST API）。 */
    CdcStatusDTO status(UUID id);
}
