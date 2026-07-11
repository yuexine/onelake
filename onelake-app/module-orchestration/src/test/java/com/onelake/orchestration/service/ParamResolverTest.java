package com.onelake.orchestration.service;

import com.onelake.orchestration.domain.entity.PipelineParam;
import com.onelake.orchestration.domain.enums.TriggerType;
import com.onelake.orchestration.repository.PipelineParamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ParamResolverTest {

    @Mock private PipelineParamRepository paramRepository;

    private ParamResolver resolver;
    private UUID tenantId;
    private UUID dagId;

    @BeforeEach
    void setup() {
        resolver = new ParamResolver(paramRepository);
        tenantId = UUID.randomUUID();
        dagId = UUID.randomUUID();
    }

    @Test
    void taskOverridesPipelineWhichOverridesGlobal() {
        when(paramRepository.findByTenantIdAndScope(tenantId, "GLOBAL"))
                .thenReturn(List.of(param("region", "cn"), param("catalog", "global_catalog")));
        when(paramRepository.findByTenantIdAndDagIdAndScope(tenantId, dagId, "PIPELINE"))
                .thenReturn(List.of(param("region", "us"), param("warehouse", "dwd")));
        when(paramRepository.findByTenantIdAndDagIdAndTaskKeyAndScope(
                tenantId, dagId, "transform", "TASK"))
                .thenReturn(List.of(param("region", "eu"), param("nullable", null)));

        Map<String, String> resolved = resolver.resolve(tenantId, dagId, "transform");

        assertThat(resolved).containsExactly(
                Map.entry("region", "eu"),
                Map.entry("catalog", "global_catalog"),
                Map.entry("warehouse", "dwd"),
                Map.entry("nullable", ""));
    }

    @Test
    void returnsEmptyMapWhenAllScopesUseDefaults() {
        when(paramRepository.findByTenantIdAndScope(tenantId, "GLOBAL")).thenReturn(List.of());
        when(paramRepository.findByTenantIdAndDagIdAndScope(tenantId, dagId, "PIPELINE"))
                .thenReturn(List.of());
        when(paramRepository.findByTenantIdAndDagIdAndTaskKeyAndScope(
                tenantId, dagId, "transform", "TASK"))
                .thenReturn(List.of());

        assertThat(resolver.resolve(tenantId, dagId, "transform")).isEmpty();
    }

    @Test
    void resolvesAllTasksFromOneUnorderedSnapshot() {
        when(paramRepository.findForResolution(
                tenantId, dagId, Set.of("pipeline_node", "task_node")))
                .thenReturn(List.of(
                        scopedParam("TASK", "task_node", "region", "eu"),
                        scopedParam("PIPELINE", null, "region", "us"),
                        scopedParam("GLOBAL", null, "region", "cn"),
                        scopedParam("GLOBAL", null, "catalog", "onelake")));

        Map<String, Map<String, String>> snapshot = resolver.resolveForTasks(
                tenantId, dagId, Set.of("pipeline_node", "task_node"));

        assertThat(snapshot.get("pipeline_node"))
                .containsEntry("region", "us")
                .containsEntry("catalog", "onelake");
        assertThat(snapshot.get("task_node"))
                .containsEntry("region", "eu")
                .containsEntry("catalog", "onelake");
        verify(paramRepository).findForResolution(
                tenantId, dagId, Set.of("pipeline_node", "task_node"));
    }

    @Test
    void runContextBuiltInsCannotBeOverriddenByUserParams() {
        UUID runId = UUID.randomUUID();
        Instant logicalDate = Instant.parse("2026-01-02T00:00:00Z");
        RunContext context = new RunContext(
                logicalDate,
                logicalDate,
                Instant.parse("2026-01-03T00:00:00Z"),
                "Asia/Shanghai",
                "NORMAL",
                null,
                TriggerType.BACKFILL);

        Map<String, String> params = context.finalParameters(runId, Map.of(
                "region", "eu",
                "run_id", "forged-run",
                "logical_date", "forged-date"));

        assertThat(params)
                .containsEntry("region", "eu")
                .containsEntry("run_id", runId.toString())
                .containsEntry("logical_date", logicalDate.toString())
                .containsEntry("bizdate", "2026-01-02")
                .containsEntry("data_interval_start", logicalDate.toString())
                .containsEntry("data_interval_end", "2026-01-03T00:00:00Z")
                .containsEntry("timezone", "Asia/Shanghai")
                .containsEntry("run_mode", "NORMAL")
                .containsEntry("trigger_type", "BACKFILL");
    }

    @Test
    void evaluatesOnlyTypedExpressionsOnceWithRunContext() {
        UUID runId = UUID.randomUUID();
        RunContext context = new RunContext(
                Instant.parse("2026-07-01T16:00:00Z"),
                Instant.parse("2026-07-01T16:00:00Z"),
                Instant.parse("2026-07-02T16:00:00Z"),
                "Asia/Shanghai",
                "NORMAL",
                null,
                TriggerType.BACKFILL);
        when(paramRepository.findForResolution(tenantId, dagId, Set.of("transform")))
                .thenReturn(List.of(
                        scopedParam("GLOBAL", null, "region", "cn", "STRING"),
                        scopedParam("PIPELINE", null, "cutoff", "${bizdate-1:yyyyMMdd}", "EXPR"),
                        scopedParam("TASK", "transform", "limit", "${upstream.extract.rowsWritten}", "EXPR"),
                        scopedParam("TASK", "transform", "literal", "${bizdate}", "STRING")));

        Map<String, Map<String, String>> resolved = resolver.resolveForTasks(
                tenantId,
                dagId,
                Set.of("transform"),
                context,
                context.builtInParameters(runId));

        assertThat(resolved.get("transform"))
                .containsEntry("region", "cn")
                .containsEntry("cutoff", "20260701")
                .containsEntry("limit", "${upstream.extract.rowsWritten}")
                .containsEntry("literal", "${bizdate}");
    }

    private PipelineParam param(String key, String value) {
        PipelineParam param = new PipelineParam();
        param.setParamKey(key);
        param.setParamValue(value);
        return param;
    }

    private PipelineParam scopedParam(String scope, String taskKey, String key, String value) {
        return scopedParam(scope, taskKey, key, value, "STRING");
    }

    private PipelineParam scopedParam(
            String scope, String taskKey, String key, String value, String valueType) {
        PipelineParam param = param(key, value);
        param.setScope(scope);
        param.setTaskKey(taskKey);
        param.setValueType(valueType);
        return param;
    }
}
