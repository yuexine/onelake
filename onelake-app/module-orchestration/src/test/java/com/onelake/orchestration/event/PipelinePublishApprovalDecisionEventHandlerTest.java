package com.onelake.orchestration.event;

import com.onelake.common.context.TenantContext;
import com.onelake.common.outbox.OutboxEvent;
import com.onelake.orchestration.service.PipelineService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PipelinePublishApprovalDecisionEventHandlerTest {

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void approvedPublishDecisionCallsPipelineServiceWithSnapshotIdentity() {
        PipelineService pipelineService = mock(PipelineService.class);
        PipelinePublishApprovalDecisionEventHandler handler =
            new PipelinePublishApprovalDecisionEventHandler(pipelineService);
        UUID tenantId = UUID.randomUUID();
        UUID applicantId = UUID.randomUUID();
        UUID dagId = UUID.randomUUID();
        OutboxEvent event = new OutboxEvent();
        event.setId(UUID.randomUUID());
        event.setPayload("""
            {"requestType":"PUBLISH","tenantId":"%s","applicantId":"%s","applicantName":"Data Engineer",
             "targetRef":"%s","snapshotChecksum":"checksum-1",
             "decision":"APPROVED","reason":"looks good"}
            """.formatted(tenantId, applicantId, dagId));

        handler.handle(event);

        verify(pipelineService).handlePublishApprovalDecision(
            dagId, "checksum-1", true, "looks good");
        org.assertj.core.api.Assertions.assertThat(TenantContext.getUsername()).isEqualTo("Data Engineer");
    }
}
