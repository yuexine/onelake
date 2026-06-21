package com.onelake.quality.service;

import com.onelake.common.context.TenantContext;
import com.onelake.common.outbox.DomainEvents;
import com.onelake.common.outbox.OutboxPublisher;
import com.onelake.quality.api.vo.CreateQualityRuleVO;
import com.onelake.quality.domain.entity.Alert;
import com.onelake.quality.domain.entity.Rule;
import com.onelake.quality.domain.entity.RunResult;
import com.onelake.quality.dto.QualityRunResultDTO;
import com.onelake.quality.repository.QualityAlertRepository;
import com.onelake.quality.repository.RuleRepository;
import com.onelake.quality.repository.RunResultRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QualityServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private RuleRepository ruleRepo;
    private RunResultRepository resultRepo;
    private QualityAlertRepository alertRepo;
    private OutboxPublisher outboxPublisher;
    private QualityService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT_ID);
        ruleRepo = mock(RuleRepository.class);
        resultRepo = mock(RunResultRepository.class);
        alertRepo = mock(QualityAlertRepository.class);
        outboxPublisher = mock(OutboxPublisher.class);
        service = new QualityService(ruleRepo, resultRepo, alertRepo, outboxPublisher);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void createRuleAppliesTenantAndDefaults() {
        when(ruleRepo.save(any(Rule.class))).thenAnswer(invocation -> {
            Rule rule = invocation.getArgument(0);
            rule.setId(UUID.randomUUID());
            return rule;
        });

        var dto = service.createRule(new CreateQualityRuleVO(
            "ods.customers",
            "phone_hash",
            "range",
            "0 <= phone_hash <= 99999",
            null,
            null,
            null
        ));

        ArgumentCaptor<Rule> captor = ArgumentCaptor.forClass(Rule.class);
        verify(ruleRepo).save(captor.capture());
        Rule saved = captor.getValue();
        assertThat(saved.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(saved.getRuleType()).isEqualTo("RANGE");
        assertThat(saved.getSeverity()).isEqualTo("BLOCK");
        assertThat(saved.getSchedule()).isEqualTo("ON_PARTITION");
        assertThat(dto.targetColumn()).isEqualTo("phone_hash");
    }

    @Test
    void runRuleRecordsResultRaisesAlertAndPublishesFailedEvent() {
        UUID ruleId = UUID.randomUUID();
        Rule rule = new Rule();
        rule.setId(ruleId);
        rule.setTenantId(TENANT_ID);
        rule.setTargetFqn("ods.customers");
        rule.setTargetColumn("amount");
        rule.setRuleType("RANGE");
        rule.setExpression("0 <= amount <= 99999");
        rule.setSeverity("BLOCK");
        rule.setEnabled(true);
        rule.setVersion(1);
        rule.setSchedule("ON_PARTITION");

        when(ruleRepo.findById(ruleId)).thenReturn(Optional.of(rule));
        when(resultRepo.save(any(RunResult.class))).thenAnswer(invocation -> {
            RunResult result = invocation.getArgument(0);
            result.setId(UUID.randomUUID());
            return result;
        });
        when(alertRepo.save(any(Alert.class))).thenAnswer(invocation -> invocation.getArgument(0));

        QualityRunResultDTO result = service.runRule(ruleId);

        assertThat(result.passed()).isFalse();
        assertThat(result.failedRows()).isEqualTo(32);
        assertThat(result.sample()).hasSize(3);
        verify(alertRepo).save(any(Alert.class));

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(outboxPublisher).publish(eq(DomainEvents.QUALITY_CHECK_FAILED), eq(ruleId.toString()), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue())
            .containsEntry("targetFqn", "ods.customers")
            .containsEntry("targetColumn", "amount")
            .containsEntry("failedRows", 32L);
    }

    @Test
    void closeAlertRejectsOtherTenantAlert() {
        UUID alertId = UUID.randomUUID();
        Alert alert = new Alert();
        alert.setId(alertId);
        alert.setTenantId(UUID.fromString("22222222-2222-2222-2222-222222222222"));
        alert.setStatus("OPEN");
        when(alertRepo.findById(alertId)).thenReturn(Optional.of(alert));

        assertThatThrownBy(() -> service.closeAlert(alertId))
            .hasMessageContaining("无权处理该质量告警");

        assertThat(alert.getStatus()).isEqualTo("OPEN");
    }
}
