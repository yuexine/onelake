package com.onelake.orchestration.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onelake.common.context.TenantContext;
import com.onelake.common.outbox.DomainEventHandler;
import com.onelake.common.outbox.DomainEvents;
import com.onelake.common.outbox.OutboxEvent;
import com.onelake.orchestration.service.PipelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/** 消费 security 审批结果，驱动真正的流水线快照发布。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PipelinePublishApprovalDecisionEventHandler implements DomainEventHandler {

    private final PipelineService pipelineService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Set<String> eventTypes() {
        return Set.of(DomainEvents.SECURITY_APPROVAL_DECIDED);
    }

    @Override
    public void handle(OutboxEvent event) {
        try {
            JsonNode payload = objectMapper.readTree(event.getPayload() == null ? "{}" : event.getPayload());
            if (!"PUBLISH".equals(payload.path("requestType").asText())) {
                return;
            }
            UUID tenantId = UUID.fromString(payload.path("tenantId").asText());
            UUID applicantId = UUID.fromString(payload.path("applicantId").asText());
            UUID dagId = UUID.fromString(payload.path("targetRef").asText());
            TenantContext.setTenantId(tenantId);
            TenantContext.setUserId(applicantId);
            TenantContext.setUsername(payload.path("applicantName").asText(null));
            pipelineService.handlePublishApprovalDecision(
                dagId,
                payload.path("snapshotChecksum").asText(),
                "APPROVED".equals(payload.path("decision").asText()),
                payload.path("reason").asText(null));
        } catch (Exception ex) {
            log.error("处理流水线发布审批结果失败，eventId={}：{}", event.getId(), ex.getMessage(), ex);
            throw new IllegalStateException("处理流水线发布审批结果失败", ex);
        }
    }
}
