package com.onelake.security.service;

import com.onelake.security.domain.entity.PiiScanRecord;

import java.util.List;
import java.util.UUID;

/**
 * PII 自动识别与分级服务。
 *
 * <p>核心能力：
 * <ul>
 *   <li>{@link #enqueueScan} — 对给定 FQN（库.表）进行 PII 猜测扫描，
 *       基于列名模式（phone/id_card/email/bank…）生成 PiiScanRecord</li>
 *   <li>{@link #listPending} — 列出待确认记录（前端 PiiScan 页面用）</li>
 *   <li>{@link #confirm} — 批量确认密级（全站随动）</li>
 * </ul>
 */
public interface PiiScanService {

    /** 对一张表做 PII 扫描，返回创建的扫描记录数（0 表示未发现敏感字段）。 */
    int enqueueScan(UUID tenantId, String tableFqn);

    /** 列出待确认记录。 */
    List<PiiScanRecord> listPending(UUID tenantId);

    /** 列出全部记录。 */
    List<PiiScanRecord> listAll(UUID tenantId);

    /** 批量确认密级（将 status 置为 CONFIRMED）。 */
    void confirm(UUID tenantId, List<UUID> recordIds);
}
