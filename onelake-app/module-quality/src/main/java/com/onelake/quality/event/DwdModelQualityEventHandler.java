package com.onelake.quality.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onelake.common.outbox.DomainEventHandler;
import com.onelake.common.outbox.DomainEvents;
import com.onelake.common.outbox.OutboxEvent;
import com.onelake.common.outbox.OutboxPublisher;
import com.onelake.common.util.JsonUtil;
import com.onelake.quality.domain.entity.Alert;
import com.onelake.quality.domain.entity.Rule;
import com.onelake.quality.domain.entity.RunResult;
import com.onelake.quality.repository.QualityAlertRepository;
import com.onelake.quality.repository.RuleRepository;
import com.onelake.quality.repository.RunResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class DwdModelQualityEventHandler implements DomainEventHandler {

    private final RuleRepository ruleRepo;
    private final RunResultRepository resultRepo;
    private final QualityAlertRepository alertRepo;
    private final OutboxPublisher outboxPublisher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Set<String> eventTypes() {
        return Set.of(DomainEvents.MODELING_MODEL_LOADED, DomainEvents.MODELING_MODEL_FAILED);
    }

    @Override
    @Transactional
    public void handle(OutboxEvent event) {
        try {
            JsonNode payload = objectMapper.readTree(event.getPayload() == null ? "{}" : event.getPayload());
            UUID tenantId = parseUuid(payload.path("tenantId").asText(""));
            UUID runId = parseUuid(payload.path("runId").asText(""));
            String targetFqn = payload.path("targetFqn").asText("");
            if (tenantId == null || targetFqn.isBlank()) {
                log.warn("DwdModelQualityEventHandler skipped event {} (missing tenantId/targetFqn)", event.getId());
                return;
            }

            List<CheckOutcome> checks = new ArrayList<>();
            checks.add(buildOutcome(event, payload));
            checks.addAll(checksOf(payload));

            for (CheckOutcome check : checks) {
                Rule rule = ensureRule(tenantId, targetFqn, check.targetColumn(), check.ruleType(), check.expression());
                RunResult result = new RunResult();
                result.setRuleId(rule.getId());
                result.setJobRunId(runId);
                result.setPassed(check.passed());
                result.setPassRate(check.passed() ? new BigDecimal("100.00") : BigDecimal.ZERO);
                result.setFailedRows(check.failedRows());
                result.setSample(JsonUtil.toJson(sampleOf(payload, check)));
                result.setCheckedAt(event.getOccurredAt() == null ? Instant.now() : event.getOccurredAt());
                resultRepo.save(result);
                if (!check.passed()) {
                    raiseAlert(tenantId, rule.getId(), targetFqn, check);
                }
                publishQualityEvent(rule, result);
            }
            publishAggregateQualityEvent(tenantId, targetFqn, runId, checks);
            log.info("DwdModelQualityEventHandler: recorded {} quality result(s) for {}", checks.size(), targetFqn);
        } catch (Exception e) {
            log.error("DwdModelQualityEventHandler failed for event {}: {}", event.getId(), e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    private List<CheckOutcome> checksOf(JsonNode payload) {
        JsonNode checks = payload.path("qualityChecks");
        if (!checks.isArray()) {
            return List.of();
        }
        List<CheckOutcome> results = new ArrayList<>();
        for (JsonNode item : checks) {
            String uniqueId = item.path("uniqueId").asText("");
            String name = item.path("name").asText(uniqueId);
            String status = item.path("status").asText("");
            boolean passed = "pass".equalsIgnoreCase(status) || "success".equalsIgnoreCase(status);
            long failures = item.path("failures").asLong(passed ? 0L : 1L);
            results.add(new CheckOutcome(
                ruleTypeOf(name, uniqueId),
                null,
                uniqueId.isBlank() ? name : uniqueId,
                passed,
                passed ? 0L : Math.max(1L, failures),
                item.path("message").asText("")
            ));
        }
        return results;
    }

    private CheckOutcome buildOutcome(OutboxEvent event, JsonNode payload) {
        boolean passed = DomainEvents.MODELING_MODEL_LOADED.equals(event.getEventType());
        return new CheckOutcome(
            "DBT_BUILD",
            null,
            "dbt build",
            passed,
            passed ? 0L : 1L,
            payload.path("errorMsg").asText("")
        );
    }

    private Rule ensureRule(UUID tenantId, String targetFqn, String targetColumn, String ruleType, String expression) {
        if ("DBT_BUILD".equalsIgnoreCase(ruleType)) {
            return ruleRepo.findByTenantIdAndTargetFqnAndRuleTypeOrderByCreatedAtDesc(tenantId, targetFqn, ruleType)
                .stream()
                .filter(rule -> isSameColumn(rule.getTargetColumn(), targetColumn))
                .findFirst()
                .orElseGet(() -> createRule(tenantId, targetFqn, targetColumn, ruleType, expression));
        }
        return ruleRepo.findFirstByTenantIdAndTargetFqnAndRuleTypeAndExpression(tenantId, targetFqn, ruleType, expression)
            .orElseGet(() -> createRule(tenantId, targetFqn, targetColumn, ruleType, expression));
    }

    private Rule createRule(UUID tenantId, String targetFqn, String targetColumn, String ruleType, String expression) {
        Rule rule = new Rule();
        rule.setTenantId(tenantId);
        rule.setTargetFqn(targetFqn);
        rule.setTargetColumn(targetColumn);
        rule.setRuleType(ruleType);
        rule.setExpression(expression);
        rule.setSeverity("BLOCK");
        rule.setEnabled(true);
        rule.setVersion(1);
        rule.setSchedule("ON_MODEL_RUN");
        rule.setCreatedAt(Instant.now());
        return ruleRepo.save(rule);
    }

    private boolean isSameColumn(String left, String right) {
        String a = left == null ? "" : left;
        String b = right == null ? "" : right;
        return a.equals(b);
    }

    private void raiseAlert(UUID tenantId, UUID ruleId, String targetFqn, CheckOutcome check) {
        Alert alert = new Alert();
        alert.setTenantId(tenantId);
        alert.setRuleId(ruleId);
        alert.setLevel("CRITICAL");
        alert.setMessage("DWD 质量门禁未通过: " + targetFqn + " / " + check.ruleType()
            + (check.message().isBlank() ? "" : " - " + check.message()));
        alert.setStatus("OPEN");
        alertRepo.save(alert);
    }

    private void publishQualityEvent(Rule rule, RunResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tenantId", rule.getTenantId().toString());
        payload.put("ruleId", rule.getId().toString());
        payload.put("resultId", result.getId() == null ? "" : result.getId().toString());
        payload.put("targetFqn", rule.getTargetFqn());
        payload.put("targetColumn", rule.getTargetColumn());
        payload.put("ruleType", rule.getRuleType());
        payload.put("passed", result.getPassed());
        payload.put("passRate", result.getPassRate());
        payload.put("failedRows", result.getFailedRows());
        payload.put("assetQualityFinal", false);
        if (result.getJobRunId() != null) {
            // 与 pipeline.task.loaded.runId 对齐，供编排 E3 在乱序事件中识别同一次资产更新。
            payload.put("runId", result.getJobRunId().toString());
        }
        outboxPublisher.publish(Boolean.TRUE.equals(result.getPassed())
            ? DomainEvents.QUALITY_CHECK_COMPLETED
            : DomainEvents.QUALITY_CHECK_FAILED, rule.getId().toString(), payload);
    }

    private void publishAggregateQualityEvent(UUID tenantId,
                                              String targetFqn,
                                              UUID runId,
                                              List<CheckOutcome> checks) {
        boolean passed = checks.stream().allMatch(CheckOutcome::passed);
        long failedRows = checks.stream().mapToLong(CheckOutcome::failedRows).sum();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tenantId", tenantId.toString());
        payload.put("targetFqn", targetFqn);
        payload.put("ruleType", "ASSET_AGGREGATE");
        payload.put("passed", passed);
        payload.put("passRate", passed ? new BigDecimal("100.00") : BigDecimal.ZERO);
        payload.put("failedRows", failedRows);
        payload.put("assetQualityFinal", true);
        if (runId != null) {
            payload.put("runId", runId.toString());
        }
        outboxPublisher.publish(passed
            ? DomainEvents.QUALITY_CHECK_COMPLETED
            : DomainEvents.QUALITY_CHECK_FAILED,
            aggregateQualityEventId(tenantId, targetFqn, runId), payload);
    }

    private String aggregateQualityEventId(UUID tenantId, String targetFqn, UUID runId) {
        if (runId != null) {
            return runId.toString();
        }
        return UUID.nameUUIDFromBytes(
            (tenantId + "|" + targetFqn).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private List<Map<String, Object>> sampleOf(JsonNode payload, CheckOutcome check) {
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("modelRunId", payload.path("runId").asText(""));
        sample.put("dagsterRunId", payload.path("dagsterRunId").asText(""));
        sample.put("status", payload.path("status").asText(""));
        sample.put("ruleType", check.ruleType());
        sample.put("expression", check.expression());
        sample.put("message", check.message());
        return List.of(sample);
    }

    private String ruleTypeOf(String name, String uniqueId) {
        String text = (name + " " + uniqueId).toLowerCase();
        if (text.contains("not_null")) return "NOT_NULL";
        if (text.contains("unique")) return "UNIQUE";
        if (text.contains("accepted_range") || text.contains("range")) return "RANGE";
        if (text.contains("accepted_values")) return "ENUM";
        return "DBT_TEST";
    }

    private UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private record CheckOutcome(
        String ruleType,
        String targetColumn,
        String expression,
        boolean passed,
        long failedRows,
        String message
    ) {}
}
