package com.onelake.security.service.impl;

import com.onelake.common.audit.AuditLogger;
import com.onelake.common.outbox.DomainEvents;
import com.onelake.common.outbox.OutboxPublisher;
import com.onelake.security.domain.entity.PiiScanRecord;
import com.onelake.security.repository.PiiScanRecordRepository;
import com.onelake.security.service.PiiScanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * 待接入后在此 impl 内替换字段来源，保留识别规则作为快速预筛。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PiiScanServiceImpl implements PiiScanService {

    private final PiiScanRecordRepository repo;
    private final AuditLogger audit;
    private final OutboxPublisher outboxPublisher;

    /** 列名模式 → (PII 类型, 建议密级, 置信度) */
    private static final Map<Pattern, PiiPattern> PATTERNS = Map.of(
        Pattern.compile("(?i).*(phone|mobile|tel).*"),
            new PiiPattern("手机号", "L3", 0.98),
        Pattern.compile("(?i).*(id_card|idcard|idcardno|id_no|identity|cert_no).*"),
            new PiiPattern("身份证", "L4", 0.95),
        Pattern.compile("(?i).*(email|e_mail|mail).*"),
            new PiiPattern("邮箱", "L3", 0.80),
        Pattern.compile("(?i).*(bank|card_no|cardno|account_no).*"),
            new PiiPattern("银行卡", "L4", 0.92),
        Pattern.compile("(?i).*(real_name|user_name|cust_name|customer_name|full_name).*"),
            new PiiPattern("姓名", "L3", 0.75),
        Pattern.compile("(?i).*(customer_no|cust_no|customer_id).*"),
            new PiiPattern("客户编号", "L3", 0.70)
    );

    record PiiPattern(String type, String level, double confidence) {}

    @Override
    @Transactional
    public int enqueueScan(UUID tenantId, String tableFqn) {
        return enqueueScan(tenantId, tableFqn, fallbackColumns(tableFqn));
    }

    @Override
    @Transactional
    public int enqueueScan(UUID tenantId, String tableFqn, List<Map<String, Object>> columns) {
        if (tenantId == null || tableFqn == null || tableFqn.isBlank()) return 0;
        log.info("PiiScan enqueueScan tenant={} table={}", tenantId, tableFqn);

        List<Map<String, Object>> scanColumns = columns == null || columns.isEmpty()
            ? fallbackColumns(tableFqn)
            : columns;

        int created = 0;
        List<Map<String, Object>> detections = new ArrayList<>();
        for (Map<String, Object> column : scanColumns) {
            String col = columnName(column);
            if (col.isBlank()) continue;
            for (var entry : PATTERNS.entrySet()) {
                if (entry.getKey().matcher(col).matches()) {
                    String recordFqn = tableFqn + "." + col;
                    if (repo.existsByTenantIdAndFqn(tenantId, recordFqn)) {
                        break;
                    }
                    PiiPattern p = entry.getValue();
                    PiiScanRecord r = new PiiScanRecord();
                    r.setTenantId(tenantId);
                    r.setFqn(recordFqn);
                    r.setPiiType(p.type());
                    r.setConfidence(p.confidence());
                    r.setSuggestLevel(suggestLevel(column, p.level()));
                    r.setStatus(PiiScanRecord.Status.PENDING);
                    r.setScannedAt(Instant.now());
                    repo.save(r);
                    detections.add(detectionPayload(recordFqn, col, p, r.getSuggestLevel(), r.getStatus()));
                    created++;
                    break;
                }
            }
        }
        audit.audit("PII_SCAN", "pii_scan_record", null,
            Map.of("tableFqn", tableFqn, "columnsScanned", scanColumns.size(), "recordsCreated", created));
        publishPiiDetected(tenantId, tableFqn, detections);
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

    private List<Map<String, Object>> fallbackColumns(String tableFqn) {
        String lower = tableFqn.toLowerCase();
        if (lower.contains("user") || lower.contains("customer") || lower.contains("person")) {
            return columns("phone", "id_card", "email", "full_name");
        }
        if (lower.contains("order") || lower.contains("trade")) {
            return columns("phone", "email");
        }
        return List.of();
    }

    private List<Map<String, Object>> columns(String... names) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String name : names) {
            Map<String, Object> column = new LinkedHashMap<>();
            column.put("name", name);
            result.add(column);
        }
        return result;
    }

    private String columnName(Map<String, Object> column) {
        if (column == null) return "";
        Object name = firstPresent(column, "target", "name", "column", "columnName", "targetColumn", "source");
        return name == null ? "" : String.valueOf(name).trim();
    }

    private Object firstPresent(Map<String, Object> column, String... keys) {
        for (String key : keys) {
            Object value = column.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String suggestLevel(Map<String, Object> column, String fallback) {
        Object raw = firstPresent(column, "classification", "suggestLevel", "level");
        if (raw == null) return fallback;
        String level = String.valueOf(raw).trim().toUpperCase();
        return level.matches("L[1-4]") ? level : fallback;
    }

    private Map<String, Object> detectionPayload(String recordFqn, String column, PiiPattern pattern,
                                                 String suggestLevel, PiiScanRecord.Status status) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fqn", recordFqn);
        payload.put("column", column);
        payload.put("piiType", pattern.type());
        payload.put("confidence", pattern.confidence());
        payload.put("suggestLevel", suggestLevel);
        payload.put("status", status.name());
        return payload;
    }

    private void publishPiiDetected(UUID tenantId, String tableFqn, List<Map<String, Object>> detections) {
        if (detections.isEmpty()) return;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tenantId", tenantId.toString());
        payload.put("tableFqn", tableFqn);
        payload.put("detectionCount", detections.size());
        payload.put("detections", detections);
        outboxPublisher.publish(DomainEvents.SECURITY_PII_DETECTED, tableFqn, payload);
    }
}
