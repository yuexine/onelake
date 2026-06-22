package com.onelake.modeling.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onelake.common.context.TenantContext;
import com.onelake.common.outbox.DomainEventHandler;
import com.onelake.common.outbox.DomainEvents;
import com.onelake.common.outbox.OutboxEvent;
import com.onelake.modeling.domain.entity.DataModel;
import com.onelake.modeling.repository.DataModelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class DwdSchemaDriftEventHandler implements DomainEventHandler {

    private final DataModelRepository modelRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Set<String> eventTypes() {
        return Set.of(DomainEvents.INTEGRATION_SCHEMA_DRIFT);
    }

    @Override
    @Transactional
    public void handle(OutboxEvent event) {
        try {
            JsonNode payload = objectMapper.readTree(event.getPayload() == null ? "{}" : event.getPayload());
            UUID tenantId = event.getTenantId();
            if (tenantId == null) {
                tenantId = parseUuid(payload.path("tenantId").asText(""));
            }
            if (tenantId == null) {
                log.warn("DwdSchemaDriftEventHandler skipped event {} (missing tenantId)", event.getId());
                return;
            }

            Set<String> sourceFqns = impactedOdsTables(payload);
            if (sourceFqns.isEmpty()) {
                log.warn("DwdSchemaDriftEventHandler skipped event {} (missing targetTables)", event.getId());
                return;
            }

            UUID previousTenant = TenantContext.getTenantId();
            try {
                TenantContext.setTenantId(tenantId);
                int affected = 0;
                for (String sourceFqn : sourceFqns) {
                    List<DataModel> models = modelRepo.findByTenantIdAndSourceFqnOrderByCreatedAtDesc(tenantId, sourceFqn);
                    for (DataModel model : models) {
                        if ("DRAFT".equalsIgnoreCase(model.getStatus())
                            || "NEEDS_REVIEW".equalsIgnoreCase(model.getStatus())) {
                            continue;
                        }
                        model.setStatus("NEEDS_REVIEW");
                        model.setUpdatedAt(Instant.now());
                        modelRepo.save(model);
                        affected += 1;
                    }
                }
                log.info("DwdSchemaDriftEventHandler: marked {} DWD model(s) as NEEDS_REVIEW for {}", affected, sourceFqns);
            } finally {
                if (previousTenant == null) {
                    TenantContext.clear();
                } else {
                    TenantContext.setTenantId(previousTenant);
                }
            }
        } catch (Exception e) {
            log.error("DwdSchemaDriftEventHandler failed for event {}: {}", event.getId(), e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    private Set<String> impactedOdsTables(JsonNode payload) {
        Set<String> tables = new LinkedHashSet<>();
        JsonNode targetTables = payload.path("targetTables");
        if (targetTables.isArray()) {
            for (JsonNode target : targetTables) {
                addOdsFqn(tables, target.asText(""));
            }
        }
        addOdsFqn(tables, payload.path("targetTable").asText(""));
        addOdsFqn(tables, payload.path("targetFqn").asText(""));
        return tables;
    }

    private void addOdsFqn(Set<String> tables, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        String normalized = value.trim().toLowerCase();
        if (normalized.startsWith("ods.")) {
            tables.add(normalized);
        }
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
