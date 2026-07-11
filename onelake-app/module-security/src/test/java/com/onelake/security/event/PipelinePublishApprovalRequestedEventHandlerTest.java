package com.onelake.security.event;

import com.onelake.common.outbox.OutboxEvent;
import com.onelake.security.service.SecurityService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PipelinePublishApprovalRequestedEventHandlerTest {

    @Test
    void requestEventCreatesPublishApprovalWithSnapshotSummary() {
        SecurityService securityService = mock(SecurityService.class);
        PipelinePublishApprovalRequestedEventHandler handler =
            new PipelinePublishApprovalRequestedEventHandler(securityService);
        UUID tenantId = UUID.randomUUID();
        UUID applicantId = UUID.randomUUID();
        UUID dagId = UUID.randomUUID();
        OutboxEvent event = new OutboxEvent();
        event.setId(UUID.randomUUID());
        event.setPayload("""
            {"requestType":"PUBLISH","tenantId":"%s","applicantId":"%s",
             "targetRef":"%s","snapshotChecksum":"checksum-1","taskCount":2}
            """.formatted(tenantId, applicantId, dagId));

        handler.handle(event);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> summaryCaptor = ArgumentCaptor.forClass(Map.class);
        verify(securityService).applyPublish(
            org.mockito.ArgumentMatchers.eq(tenantId),
            org.mockito.ArgumentMatchers.eq(applicantId),
            org.mockito.ArgumentMatchers.eq(dagId.toString()),
            summaryCaptor.capture());
        assertThat(summaryCaptor.getValue())
            .containsEntry("snapshotChecksum", "checksum-1")
            .containsEntry("taskCount", 2);
    }
}
