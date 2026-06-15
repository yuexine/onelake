package com.onelake.security.service.impl;

import com.onelake.common.audit.AuditLogger;
import com.onelake.security.domain.entity.PiiScanRecord;
import com.onelake.security.repository.PiiScanRecordRepository;
import com.onelake.security.service.PiiScanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * PII 扫描服务实现。
 *
 * <p>当前使用"列名模式匹配"作为识别策略（非数据采样），适合快速能跑通的 MVP：
 * <ul>
 *   <li>phone / mobile → 手机号 → L3</li>
 *   <li>id_card / idcard → 身份证 → L4</li>
 *   <li>email / mail → 邮箱 → L3</li>
 *   <li>bank / card_no → 银行卡 → L4</li>
 *   <li>name / user_name → 姓名 → L3</li>
 * </ul>
 *
 * <p>真实数据采样扫描（读取 100 行 → 正则匹配）需要 Trino JDBC 查询能力，
 * 待接入后在此 impl 内替换 {@link #detectColumns(String)} 的逻辑。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PiiScanServiceImpl implements PiiScanService {

    private final PiiScanRecordRepository repo;
    private final AuditLogger audit;

    /** 列名模式 → (PII 类型, 建议密级, 置信度) */
    private static final Map<Pattern, PiiPattern> PATTERNS = Map.of(
        Pattern.compile("(?i).*(phone|mobile|tel).*"),
            new PiiPattern("手机号", "L3", 0.98),
        Pattern.compile("(?i).*(id_card|idcard|idcardno).*"),
            new PiiPattern("身份证", "L4", 0.95),
        Pattern.compile("(?i).*(email|e_mail|mail).*"),
            new PiiPattern("邮箱", "L3", 0.80),
        Pattern.compile("(?i).*(bank|card_no|cardno|account_no).*"),
            new PiiPattern("银行卡", "L4", 0.92),
        Pattern.compile("(?i).*(real_name|user_name|cust_name|customer_name).*"),
            new PiiPattern("姓名", "L3", 0.75)
    );

    record PiiPattern(String type, String level, double confidence) {}

    @Override
    @Transactional
    public int enqueueScan(UUID tenantId, String tableFqn) {
        if (tenantId == null || tableFqn == null || tableFqn.isBlank()) return 0;
        log.info("PiiScan enqueueScan tenant={} table={}", tenantId, tableFqn);

        // 当前 stub：无法实际读源端列定义，基于表名猜测典型敏感字段
        // TODO: 接入 Trino JDBC `SHOW COLUMNS FROM <fqn>` 获取真实列名后匹配
        List<String> guessedColumns = detectColumns(tableFqn);

        int created = 0;
        for (String col : guessedColumns) {
            for (var entry : PATTERNS.entrySet()) {
                if (entry.getKey().matcher(col).matches()) {
                    PiiPattern p = entry.getValue();
                    PiiScanRecord r = new PiiScanRecord();
                    r.setTenantId(tenantId);
                    r.setFqn(tableFqn + "." + col);
                    r.setPiiType(p.type());
                    r.setConfidence(p.confidence());
                    r.setSuggestLevel(p.level());
                    r.setStatus(PiiScanRecord.Status.PENDING);
                    r.setScannedAt(Instant.now());
                    repo.save(r);
                    created++;
                    break;
                }
            }
        }
        audit.audit("PII_SCAN", "pii_scan_record", null,
            Map.of("tableFqn", tableFqn, "recordsCreated", created));
        log.info("PiiScan completed for {}: {} sensitive fields detected", tableFqn, created);
        return created;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PiiScanRecord> listPending(UUID tenantId) {
        return repo.findByTenantIdAndStatus(tenantId, PiiScanRecord.Status.PENDING);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PiiScanRecord> listAll(UUID tenantId) {
        return repo.findByTenantIdOrderByScannedAtDesc(tenantId);
    }

    @Override
    @Transactional
    public void confirm(UUID tenantId, List<UUID> recordIds) {
        for (UUID id : recordIds) {
            repo.findById(id).ifPresent(r -> {
                if (r.getTenantId().equals(tenantId)) {
                    r.setStatus(PiiScanRecord.Status.CONFIRMED);
                    r.setConfirmedAt(Instant.now());
                    repo.save(r);
                }
            });
        }
        audit.audit("PII_CONFIRM", "pii_scan_record", null,
            Map.of("confirmedCount", recordIds.size()));
    }

    /**
     * 占位：基于表名猜测可能存在的敏感列。
     * 真实实现应调 Trino SHOW COLUMNS 或读 integration.source_schema_snapshot。
     */
    private List<String> detectColumns(String tableFqn) {
        // 简单启发：如果表名含 user/customer/person，猜测有 phone+id_card+email
        String lower = tableFqn.toLowerCase();
        if (lower.contains("user") || lower.contains("customer") || lower.contains("person")) {
            return List.of("phone", "id_card", "email", "name");
        }
        if (lower.contains("order") || lower.contains("trade")) {
            return List.of("phone", "email");
        }
        return List.of();
    }
}
