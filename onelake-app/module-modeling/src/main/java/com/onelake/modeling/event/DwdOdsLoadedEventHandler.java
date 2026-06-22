package com.onelake.modeling.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onelake.common.context.TenantContext;
import com.onelake.common.outbox.DomainEventHandler;
import com.onelake.common.outbox.DomainEvents;
import com.onelake.common.outbox.OutboxEvent;
import com.onelake.modeling.domain.entity.DataModel;
import com.onelake.modeling.dto.DwdModelRunRequest;
import com.onelake.modeling.repository.DataModelRepository;
import com.onelake.modeling.repository.DataModelRunRepository;
import com.onelake.modeling.service.DwdModelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class DwdOdsLoadedEventHandler implements DomainEventHandler {

    private static final List<String> ACTIVE_STATUSES = List.of("QUEUED", "RUNNING");

    private final DataModelRepository modelRepo;
    private final DataModelRunRepository runRepo;
    private final DwdModelService dwdModelService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Set<String> eventTypes() {
        return Set.of(DomainEvents.INTEGRATION_TABLE_LOADED);
    }

    @Override
    public void handle(OutboxEvent event) {
        try {
            JsonNode payload = objectMapper.readTree(event.getPayload() == null ? "{}" : event.getPayload());
            String targetTable = payload.path("targetTable").asText("");
            String tenantIdRaw = payload.path("tenantId").asText("");
            if (targetTable.isBlank() || tenantIdRaw.isBlank()) {
                log.warn("DwdOdsLoadedEventHandler skipped event {} (missing targetTable/tenantId)", event.getId());
                return;
            }
            if (!isOdsTable(targetTable, payload.path("namespace").asText(""))) {
                log.debug("DwdOdsLoadedEventHandler skipped non-ODS table {}", targetTable);
                return;
            }

            UUID tenantId;
            try {
                tenantId = UUID.fromString(tenantIdRaw);
            } catch (IllegalArgumentException e) {
                log.warn("DwdOdsLoadedEventHandler skipped event {} (bad tenantId {})", event.getId(), tenantIdRaw);
                return;
            }
            UUID sourceRunId = parseUuid(payload.path("runId").asText(""));
            UUID previousTenant = TenantContext.getTenantId();
            try {
                TenantContext.setTenantId(tenantId);
                triggerDependentModels(tenantId, targetTable, sourceRunId, event.getOccurredAt());
            } finally {
                if (previousTenant == null) {
                    TenantContext.clear();
                } else {
                    TenantContext.setTenantId(previousTenant);
                }
            }
        } catch (Exception e) {
            log.error("DwdOdsLoadedEventHandler failed for event {}: {}", event.getId(), e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    private void triggerDependentModels(UUID tenantId, String sourceFqn, UUID sourceRunId, Instant eventOccurredAt) {
        List<DataModel> models = modelRepo.findByTenantIdAndSourceFqnOrderByCreatedAtDesc(tenantId, sourceFqn)
            .stream()
            .filter(model -> "VALIDATED".equalsIgnoreCase(model.getStatus()))
            .toList();
        if (models.isEmpty()) {
            log.debug("DwdOdsLoadedEventHandler: no validated DWD models depend on {}", sourceFqn);
            return;
        }
        for (DataModel model : models) {
            if (isHistoricalEvent(model, eventOccurredAt)) {
                log.info("DwdOdsLoadedEventHandler: model {} is newer than ODS event {}, skip historical trigger",
                    model.getId(), eventOccurredAt);
                continue;
            }
            if (sourceRunId != null && runRepo.existsByModelIdAndSourceIntegrationRunId(model.getId(), sourceRunId)) {
                log.info("DwdOdsLoadedEventHandler: model {} already triggered for sync run {}", model.getId(), sourceRunId);
                continue;
            }
            if (runRepo.existsByModelIdAndStatusIn(model.getId(), ACTIVE_STATUSES)) {
                log.info("DwdOdsLoadedEventHandler: model {} has active run, skip ODS event trigger", model.getId());
                continue;
            }
            dwdModelService.run(model.getId(), new DwdModelRunRequest("ODS_EVENT", sourceRunId));
            log.info("DwdOdsLoadedEventHandler: triggered DWD model {} after ODS {}", model.getId(), sourceFqn);
        }
    }

    private boolean isOdsTable(String targetTable, String namespace) {
        if (targetTable.toLowerCase().startsWith("ods.")) {
            return true;
        }
        return namespace != null && namespace.equalsIgnoreCase("ods");
    }

    private boolean isHistoricalEvent(DataModel model, Instant eventOccurredAt) {
        Instant modelReadyAt = model.getUpdatedAt() == null ? model.getCreatedAt() : model.getUpdatedAt();
        return eventOccurredAt != null && modelReadyAt != null && eventOccurredAt.isBefore(modelReadyAt);
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
}
