package com.onelake.security.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.onelake.common.outbox.DomainEventHandler;
import com.onelake.common.outbox.DomainEvents;
import com.onelake.common.outbox.OutboxEvent;
import com.onelake.security.service.SecurityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 将编排模块的发布申请事件转换为 security.approval_request。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PipelinePublishApprovalRequestedEventHandler implements DomainEventHandler {

    private final SecurityService securityService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Set<String> eventTypes() {
        return Set.of(DomainEvents.PIPELINE_PUBLISH_APPROVAL_REQUESTED);
    }

    @Override
    public void handle(OutboxEvent event) {
        try {
            JsonNode payload = objectMapper.readTree(event.getPayload() == null ? "{}" : event.getPayload());
            UUID tenantId = UUID.fromString(payload.path("tenantId").asText());
            UUID applicantId = UUID.fromString(payload.path("applicantId").asText());
            String targetRef = payload.path("targetRef").asText();
            Map<String, Object> summary = objectMapper.convertValue(payload, new TypeReference<>() {});
            securityService.applyPublish(tenantId, applicantId, targetRef, summary);
        } catch (Exception ex) {
            log.error("创建流水线发布审批失败，eventId={}：{}", event.getId(), ex.getMessage(), ex);
            throw new IllegalStateException("创建流水线发布审批失败", ex);
        }
    }
}
