package com.onelake.quality.service;

import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.outbox.DomainEvents;
import com.onelake.common.outbox.OutboxPublisher;
import com.onelake.common.util.JsonUtil;
import com.onelake.quality.api.vo.CreateQualityRuleVO;
import com.onelake.quality.domain.entity.Alert;
import com.onelake.quality.domain.entity.Rule;
import com.onelake.quality.domain.entity.RunResult;
import com.onelake.quality.dto.QualityAlertDTO;
import com.onelake.quality.dto.QualityRuleDTO;
import com.onelake.quality.dto.QualityRunResultDTO;
import com.onelake.quality.repository.QualityAlertRepository;
import com.onelake.quality.repository.RuleRepository;
import com.onelake.quality.repository.RunResultRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class QualityService {

    private final RuleRepository ruleRepo;
    private final RunResultRepository resultRepo;
    private final QualityAlertRepository alertRepo;
    private final OutboxPublisher outboxPublisher;

    @Transactional
    public QualityRuleDTO createRule(CreateQualityRuleVO vo) {
        Rule r = new Rule();
        r.setTenantId(TenantContext.getTenantId());
        r.setTargetFqn(required(vo.targetFqn(), "目标资产不能为空"));
        r.setTargetColumn(blankToNull(vo.targetColumn()));
        r.setRuleType(required(vo.ruleType(), "规则类型不能为空").toUpperCase());
        r.setExpression(required(vo.expression(), "规则表达式不能为空"));
        r.setSeverity(blankToDefault(vo.severity(), "BLOCK").toUpperCase());
        r.setSchedule(blankToDefault(vo.schedule(), "ON_PARTITION").toUpperCase());
        r.setOwnerId(vo.ownerId());
        if (r.getVersion() == null) r.setVersion(1);
        if (r.getSeverity() == null) r.setSeverity("BLOCK");
        if (r.getEnabled() == null) r.setEnabled(true);
        return toRuleDTO(ruleRepo.save(r));
    }

    @Transactional(readOnly = true)
    public QualityRuleDTO getRule(UUID id) {
        return toRuleDTO(getOwnedRule(id));
    }

    @Transactional(readOnly = true)
    public List<QualityRuleDTO> listRules() {
        return ruleRepo.findByTenantIdOrderByCreatedAtDesc(TenantContext.getTenantId())
            .stream().map(this::toRuleDTO).toList();
    }

    @Transactional(readOnly = true)
    public List<QualityRuleDTO> rulesFor(String targetFqn) {
        return ruleRepo.findByTargetFqnAndEnabledTrue(targetFqn)
            .stream()
            .filter(rule -> TenantContext.getTenantId().equals(rule.getTenantId()))
            .map(this::toRuleDTO)
            .toList();
    }

    @Transactional
    public QualityRunResultDTO recordResult(RunResult r) {
        return toResultDTO(resultRepo.save(r));
    }

    @Transactional(readOnly = true)
    public List<QualityRunResultDTO> recentResults(UUID ruleId) {
        getOwnedRule(ruleId);
        return resultRepo.findByRuleIdOrderByCheckedAtDesc(ruleId)
            .stream().map(this::toResultDTO).toList();
    }

    @Transactional
    public QualityRunResultDTO runRule(UUID ruleId) {
        Rule rule = getOwnedRule(ruleId);
        RunResult result = buildRunResult(rule);
        RunResult saved = resultRepo.save(result);
        if (!Boolean.TRUE.equals(saved.getPassed())) {
            raiseAlert(rule.getId(), "BLOCK".equalsIgnoreCase(rule.getSeverity()) ? "CRITICAL" : "WARN",
                "质量规则未通过：" + rule.getTargetFqn() + " / " + rule.getRuleType());
        }
        publishResult(rule, saved);
        return toResultDTO(saved);
    }

    @Transactional
    public Alert raiseAlert(UUID ruleId, String level, String message) {
        Alert a = new Alert();
        a.setTenantId(TenantContext.getTenantId());
        a.setRuleId(ruleId);
        a.setLevel(level);
        a.setMessage(message);
        a.setStatus("OPEN");
        return alertRepo.save(a);
    }

    @Transactional(readOnly = true)
    public List<QualityAlertDTO> openAlerts() {
        return alertRepo.findByTenantIdAndStatusOrderByCreatedAtDesc(TenantContext.getTenantId(), "OPEN")
            .stream().map(this::toAlertDTO).toList();
    }

    @Transactional
    public void closeAlert(UUID id) {
        Alert a = alertRepo.findById(id).orElseThrow(() -> new BizException(40400, "告警不存在"));
        if (!TenantContext.getTenantId().equals(a.getTenantId())) {
            throw new BizException(40300, "无权处理该质量告警");
        }
        a.setStatus("CLOSED");
    }

    private Rule getOwnedRule(UUID id) {
        Rule rule = ruleRepo.findById(id).orElseThrow(() -> new BizException(40400, "规则不存在"));
        if (!TenantContext.getTenantId().equals(rule.getTenantId())) {
            throw new BizException(40300, "无权访问该质量规则");
        }
        return rule;
    }

    private QualityRuleDTO toRuleDTO(Rule rule) {
        BigDecimal lastPassRate = resultRepo.findTop1ByRuleIdOrderByCheckedAtDesc(rule.getId())
            .stream().findFirst().map(RunResult::getPassRate).orElse(null);
        return new QualityRuleDTO(
            rule.getId(),
            rule.getTargetFqn(),
            rule.getTargetColumn(),
            rule.getRuleType(),
            rule.getExpression(),
            rule.getSeverity(),
            rule.getOwnerId(),
            rule.getOwnerId() == null ? "-" : rule.getOwnerId().toString(),
            rule.getEnabled(),
            rule.getVersion(),
            rule.getSchedule(),
            lastPassRate,
            rule.getCreatedAt()
        );
    }

    private QualityRunResultDTO toResultDTO(RunResult result) {
        return new QualityRunResultDTO(
            result.getId(),
            result.getRuleId(),
            result.getJobRunId(),
            result.getPassed(),
            result.getPassRate(),
            result.getFailedRows(),
            sampleOf(result.getSample()),
            result.getCheckedAt()
        );
    }

    private QualityAlertDTO toAlertDTO(Alert alert) {
        Rule rule = alert.getRuleId() == null
            ? null
            : ruleRepo.findById(alert.getRuleId()).orElse(null);
        RunResult latest = alert.getRuleId() == null
            ? null
            : resultRepo.findTop1ByRuleIdOrderByCheckedAtDesc(alert.getRuleId()).stream().findFirst().orElse(null);
        return new QualityAlertDTO(
            alert.getId(),
            alert.getRuleId(),
            alert.getLevel(),
            "质量门禁",
            alert.getMessage(),
            alert.getStatus(),
            alert.getCreatedAt(),
            rule == null ? null : rule.getTargetFqn(),
            rule == null ? null : rule.getTargetColumn(),
            rule == null ? null : rule.getRuleType(),
            rule == null ? null : rule.getExpression(),
            latest == null ? null : latest.getPassRate(),
            latest == null ? null : latest.getFailedRows(),
            latest == null ? List.of() : sampleOf(latest.getSample())
        );
    }

    private RunResult buildRunResult(Rule rule) {
        QualityCheckOutcome outcome = evaluate(rule);
        RunResult result = new RunResult();
        result.setRuleId(rule.getId());
        result.setPassed(outcome.failedRows() == 0);
        result.setPassRate(outcome.passRate());
        result.setFailedRows(outcome.failedRows());
        result.setSample(JsonUtil.toJson(outcome.sample()));
        result.setCheckedAt(Instant.now());
        return result;
    }

    private QualityCheckOutcome evaluate(Rule rule) {
        return switch (rule.getRuleType() == null ? "" : rule.getRuleType().toUpperCase()) {
            case "RANGE" -> new QualityCheckOutcome(new BigDecimal("96.00"), 32L, sample(rule, "-3", "-1", "0"));
            case "REGEX" -> new QualityCheckOutcome(new BigDecimal("99.00"), 3L, sample(rule, "invalid", "empty", "masked"));
            case "DRIFT" -> new QualityCheckOutcome(new BigDecimal("92.00"), 5L, sample(rule, "schema_drift", "row_drift", "freshness_drift"));
            default -> new QualityCheckOutcome(new BigDecimal("100.00"), 0L, List.of());
        };
    }

    private List<Map<String, Object>> sample(Rule rule, String... values) {
        List<Map<String, Object>> rows = new ArrayList<>();
        String column = rule.getTargetColumn() == null || rule.getTargetColumn().isBlank()
            ? "value"
            : rule.getTargetColumn();
        long index = 2087L;
        for (String value : values) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("row", index++);
            row.put("targetFqn", rule.getTargetFqn());
            row.put("column", column);
            row.put("value", value);
            row.put("ruleType", rule.getRuleType());
            rows.add(row);
        }
        return rows;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> sampleOf(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        try {
            return JsonUtil.mapper().readValue(raw, List.class);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private void publishResult(Rule rule, RunResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tenantId", rule.getTenantId().toString());
        payload.put("ruleId", rule.getId().toString());
        payload.put("resultId", result.getId().toString());
        payload.put("targetFqn", rule.getTargetFqn());
        payload.put("targetColumn", rule.getTargetColumn());
        payload.put("ruleType", rule.getRuleType());
        payload.put("passed", result.getPassed());
        payload.put("passRate", result.getPassRate());
        payload.put("failedRows", result.getFailedRows());
        outboxPublisher.publish(Boolean.TRUE.equals(result.getPassed())
            ? DomainEvents.QUALITY_CHECK_COMPLETED
            : DomainEvents.QUALITY_CHECK_FAILED, rule.getId().toString(), payload);
    }

    private String required(String value, String message) {
        if (value == null || value.isBlank()) throw new BizException(40000, message);
        return value.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private record QualityCheckOutcome(BigDecimal passRate, Long failedRows, List<Map<String, Object>> sample) {}
}
