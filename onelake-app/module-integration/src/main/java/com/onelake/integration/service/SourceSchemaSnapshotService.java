package com.onelake.integration.service;

import com.onelake.integration.dto.SourceSchemaSnapshotDTO;

import java.util.List;
import java.util.UUID;

/**
 * 源端 Schema 快照与漂移检测服务。
 *
 * <p>核心能力：
 * <ul>
 *   <li>手动触发快照（从源端拉取 columns + 计算 checksum）</li>
 *   <li>查询快照历史</li>
 *   <li>对比相邻快照，检测 schema 漂移（新增/删除/类型变更）</li>
 * </ul>
 */
public interface SourceSchemaSnapshotService {

    /** 触发一次快照。返回新建的快照；如果与上一次 checksum 相同则不写入新行，返回最近一次。 */
    SourceSchemaSnapshotDTO capture(UUID sourceId, String objectName);

    /** 列出某数据源下所有对象的快照历史（按时间倒序）。 */
    List<SourceSchemaSnapshotDTO> listBySource(UUID sourceId);

    /** 列出某数据源 + 某个对象（库.表）的快照历史。 */
    List<SourceSchemaSnapshotDTO> listByObject(UUID sourceId, String objectName);

    /** 对比最近两次快照，返回漂移检测结果（若仅一次或零次快照，则视为无漂移）。 */
    DriftResult detectDrift(UUID sourceId, String objectName);

    /** 漂移检测结果。 */
    record DriftResult(
            String objectName,
            boolean drifted,
            String previousChecksum,
            String currentChecksum,
            List<ColumnChange> changes
    ) {}

    record ColumnChange(
            String columnName,
            String changeType,   // ADD / REMOVE / TYPE_CHANGE
            String previousType,
            String currentType
    ) {}
}
