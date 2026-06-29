package com.onelake.orchestration.service;

import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.outbox.OutboxPublisher;
import com.onelake.orchestration.domain.entity.Dag;
import com.onelake.orchestration.dto.PipelineValidationResult;
import com.onelake.orchestration.repository.DagRepository;
import com.onelake.orchestration.repository.PipelineTaskEdgeRepository;
import com.onelake.orchestration.repository.PipelineTaskRepository;
import com.onelake.orchestration.repository.TaskRunRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P4-D tests for {@link PipelineService#updatePipelineStatus} status machine.
 *
 * <p>Verifies DRAFT→VALIDATED→PUBLISHED transitions + Outbox event on PUBLISHED.
 */
@ExtendWith(MockitoExtension.class)
class PipelineStatusMachineTest {

    @Mock private DagRepository dagRepo;
    @Mock private PipelineTaskRepository taskRepo;
    @Mock private PipelineTaskEdgeRepository edgeRepo;
    @Mock private TaskRunRepository taskRunRepo;
    @Mock private PipelineCompileService compileService;
    @Mock private ObjectProvider<OutboxPublisher> outboxProvider;
    @Mock private OutboxPublisher outboxPublisher;

    private PipelineService service;
    private UUID tenantId;
    private UUID dagId;

    @BeforeEach
    void setup() {
        service = new PipelineService(dagRepo, taskRepo, edgeRepo, taskRunRepo,
                compileService, outboxProvider);
        tenantId = UUID.randomUUID();
        dagId = UUID.randomUUID();
        TenantContext.setTenantId(tenantId);
        TenantContext.setUserId(UUID.randomUUID());
        lenient().doAnswer(inv -> {
            Dag d = inv.getArgument(0);
            if (d.getId() == null) d.setId(UUID.randomUUID());
            return d;
        }).when(dagRepo).save(any(Dag.class));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void validatedTransitionRequiresValidationPass() {
        Dag dag = dag("DRAFT");
        when(dagRepo.findByIdAndTenantId(dagId, tenantId)).thenReturn(Optional.of(dag));
        // compile returns invalid plan → validate() returns invalid result
        lenient().when(compileService.compile(dagId)).thenReturn(invalidCompileResult());
        lenient().when(taskRepo.findByDagIdOrderByCreatedAtAsc(dagId)).thenReturn(List.of());

        assertThatThrownBy(() -> service.updatePipelineStatus(dagId, "VALIDATED"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("校验未通过");
    }

    @Test
    void validatedTransitionSucceedsWhenCompilePasses() {
        Dag dag = dag("DRAFT");
        when(dagRepo.findByIdAndTenantId(dagId, tenantId)).thenReturn(Optional.of(dag));
        when(compileService.compile(dagId)).thenReturn(validCompileResult());
        lenient().when(taskRepo.findByDagIdOrderByCreatedAtAsc(dagId)).thenReturn(List.of());
        lenient().when(edgeRepo.findByDagId(dagId)).thenReturn(List.of());

        Dag updated = service.updatePipelineStatus(dagId, "VALIDATED");

        assertThat(updated.getStatus()).isEqualTo("VALIDATED");
        assertThat(updated.getVersion()).isEqualTo(2); // incremented
    }

    @Test
    void publishedFromDraftRejected() {
        Dag dag = dag("DRAFT");
        when(dagRepo.findByIdAndTenantId(dagId, tenantId)).thenReturn(Optional.of(dag));

        assertThatThrownBy(() -> service.updatePipelineStatus(dagId, "PUBLISHED"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("VALIDATED");
    }

    @Test
    void publishedEmitsOutboxEvent() {
        Dag dag = dag("VALIDATED");
        when(dagRepo.findByIdAndTenantId(dagId, tenantId)).thenReturn(Optional.of(dag));
        when(outboxProvider.getIfAvailable()).thenReturn(outboxPublisher);
        when(taskRepo.findByDagIdOrderByCreatedAtAsc(dagId)).thenReturn(List.of());

        service.updatePipelineStatus(dagId, "PUBLISHED");

        ArgumentCaptor<String> typeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<java.util.Map<String, Object>> payloadCaptor =
                ArgumentCaptor.forClass(java.util.Map.class);
        verify(outboxPublisher).publish(typeCaptor.capture(), anyString(), payloadCaptor.capture());
        assertThat(typeCaptor.getValue()).isEqualTo("pipeline.published");
        assertThat(payloadCaptor.getValue().get("pipelineId")).isEqualTo(dagId.toString());
        assertThat(payloadCaptor.getValue().get("version")).isEqualTo(2);
    }

    @Test
    void sameStatusIsNoOp() {
        Dag dag = dag("DRAFT");
        when(dagRepo.findByIdAndTenantId(dagId, tenantId)).thenReturn(Optional.of(dag));

        Dag updated = service.updatePipelineStatus(dagId, "DRAFT");

        assertThat(updated.getStatus()).isEqualTo("DRAFT");
        assertThat(updated.getVersion()).isEqualTo(1); // unchanged
    }

    @Test
    void invalidStatusRejected() {
        Dag dag = dag("DRAFT");
        when(dagRepo.findByIdAndTenantId(dagId, tenantId)).thenReturn(Optional.of(dag));

        assertThatThrownBy(() -> service.updatePipelineStatus(dagId, "ARCHIVED"))
                .isInstanceOf(BizException.class);
    }

    private Dag dag(String status) {
        Dag d = new Dag();
        d.setId(dagId);
        d.setTenantId(tenantId);
        d.setName("test_pipeline");
        d.setDagsterJob("onelake_pipeline_run");
        d.setDefinition("{}");
        d.setEnabled(true);
        d.setVersion(1);
        d.setStatus(status);
        d.setPipelineKind("BLANK");
        d.setEngine("SPARK");
        return d;
    }

    private com.onelake.orchestration.dto.PipelineCompileResult validCompileResult() {
        return new com.onelake.orchestration.dto.PipelineCompileResult(
                dagId, "pipeline_" + dagId, tenantId, List.of(), true, List.of());
    }

    private com.onelake.orchestration.dto.PipelineCompileResult invalidCompileResult() {
        return new com.onelake.orchestration.dto.PipelineCompileResult(
                dagId, "pipeline_" + dagId, tenantId, List.of(), false, List.of("cycle"));
    }
}
