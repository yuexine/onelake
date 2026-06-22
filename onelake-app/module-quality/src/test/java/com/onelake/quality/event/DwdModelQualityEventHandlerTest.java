package com.onelake.quality.event;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DwdModelQualityEventHandlerTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RUN_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private RuleRepository ruleRepo;
    private RunResultRepository resultRepo;
    private QualityAlertRepository alertRepo;
    private OutboxPublisher outboxPublisher;
    private DwdModelQualityEventHandler handler;

    @BeforeEach
    void setUp() {
        ruleRepo = mock(RuleRepository.class);
        resultRepo = mock(RunResultRepository.class);
        alertRepo = mock(QualityAlertRepository.class);
        outboxPublisher = mock(OutboxPublisher.class);
        when(ruleRepo.findFirstByTenantIdAndTargetFqnAndRuleTypeAndExpression(any(), anyString(), anyString(), anyString()))
            .thenReturn(Optional.empty());
        when(ruleRepo.findByTenantIdAndTargetFqnAndRuleTypeOrderByCreatedAtDesc(any(), anyString(), anyString()))
            .thenReturn(List.of());
        when(ruleRepo.save(any(Rule.class))).thenAnswer(invocation -> {
            Rule rule = invocation.getArgument(0);
            rule.setId(UUID.randomUUID());
            return rule;
        });
        when(resultRepo.save(any(RunResult.class))).thenAnswer(invocation -> {
            RunResult result = invocation.getArgument(0);
            result.setId(UUID.randomUUID());
            return result;
        });
        when(alertRepo.save(any(Alert.class))).thenAnswer(invocation -> invocation.getArgument(0));
        handler = new DwdModelQualityEventHandler(ruleRepo, resultRepo, alertRepo, outboxPublisher);
    }

    @Test
    void recordsDbtTestResultsFromLoadedEvent() {
        handler.handle(event(DomainEvents.MODELING_MODEL_LOADED, Map.of(
            "tenantId", TENANT_ID.toString(),
            "runId", RUN_ID.toString(),
            "status", "SUCCEEDED",
            "targetFqn", "dwd.dwd_trade_orders_df",
            "dagsterRunId", "dagster-run-1",
            "qualityChecks", List.of(
                Map.of("uniqueId", "test.onelake.not_null_dwd_trade_orders_df_order_id.abc", "name", "not_null_dwd_trade_orders_df_order_id", "status", "pass", "failures", 0),
                Map.of("uniqueId", "test.onelake.unique_dwd_trade_orders_df_order_id.def", "name", "unique_dwd_trade_orders_df_order_id", "status", "pass", "failures", 0)
            )
        )));

        ArgumentCaptor<Rule> ruleCaptor = ArgumentCaptor.forClass(Rule.class);
        verify(ruleRepo, org.mockito.Mockito.times(3)).save(ruleCaptor.capture());
        assertThat(ruleCaptor.getAllValues())
            .extracting(Rule::getRuleType)
            .containsExactly("DBT_BUILD", "NOT_NULL", "UNIQUE");

        ArgumentCaptor<RunResult> resultCaptor = ArgumentCaptor.forClass(RunResult.class);
        verify(resultRepo, org.mockito.Mockito.times(3)).save(resultCaptor.capture());
        assertThat(resultCaptor.getAllValues()).allSatisfy(result -> {
            assertThat(result.getJobRunId()).isEqualTo(RUN_ID);
            assertThat(result.getPassed()).isTrue();
            assertThat(result.getPassRate()).isEqualByComparingTo("100.00");
            assertThat(result.getFailedRows()).isZero();
        });
        verify(alertRepo, never()).save(any());
        verify(outboxPublisher, org.mockito.Mockito.times(3))
            .publish(eq(DomainEvents.QUALITY_CHECK_COMPLETED), anyString(), any(Map.class));
    }

    @Test
    void recordsFailedModelAsQualityFailureAndAlert() {
        handler.handle(event(DomainEvents.MODELING_MODEL_FAILED, Map.of(
            "tenantId", TENANT_ID.toString(),
            "runId", RUN_ID.toString(),
            "status", "FAILED",
            "targetFqn", "dwd.dwd_trade_orders_df",
            "dagsterRunId", "dagster-run-1",
            "errorMsg", "dbt test failed"
        )));

        ArgumentCaptor<RunResult> resultCaptor = ArgumentCaptor.forClass(RunResult.class);
        verify(resultRepo).save(resultCaptor.capture());
        assertThat(resultCaptor.getValue().getPassed()).isFalse();
        assertThat(resultCaptor.getValue().getPassRate()).isEqualByComparingTo("0");

        ArgumentCaptor<Alert> alertCaptor = ArgumentCaptor.forClass(Alert.class);
        verify(alertRepo).save(alertCaptor.capture());
        assertThat(alertCaptor.getValue().getTenantId()).isEqualTo(TENANT_ID);
        assertThat(alertCaptor.getValue().getLevel()).isEqualTo("CRITICAL");
        assertThat(alertCaptor.getValue().getMessage()).contains("DWD 质量门禁未通过", "dbt test failed");

        verify(outboxPublisher).publish(eq(DomainEvents.QUALITY_CHECK_FAILED), anyString(), any(Map.class));
    }

    @Test
    void skipsInvalidPayload() {
        handler.handle(event(DomainEvents.MODELING_MODEL_LOADED, Map.of(
            "tenantId", "bad",
            "targetFqn", "dwd.dwd_trade_orders_df"
        )));

        verify(ruleRepo, never()).save(any());
        verify(resultRepo, never()).save(any());
    }

    private OutboxEvent event(String eventType, Map<String, Object> payload) {
        OutboxEvent event = new OutboxEvent();
        event.setId(UUID.randomUUID());
        event.setTenantId(TENANT_ID);
        event.setEventType(eventType);
        event.setAggregateId(RUN_ID.toString());
        event.setPayload(JsonUtil.toJson(payload));
        return event;
    }
}
