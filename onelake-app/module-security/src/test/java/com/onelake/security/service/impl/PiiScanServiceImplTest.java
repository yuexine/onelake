package com.onelake.security.service.impl;

import com.onelake.common.audit.AuditLogger;
import com.onelake.common.outbox.DomainEvents;
import com.onelake.common.outbox.OutboxPublisher;
import com.onelake.security.domain.entity.PiiScanRecord;
import com.onelake.security.repository.PiiScanRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PiiScanServiceImplTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private PiiScanRecordRepository repo;
    private AuditLogger audit;
    private OutboxPublisher outboxPublisher;
    private PiiScanServiceImpl service;

    @BeforeEach
    void setUp() {
        repo = mock(PiiScanRecordRepository.class);
        audit = mock(AuditLogger.class);
        outboxPublisher = mock(OutboxPublisher.class);
        service = new PiiScanServiceImpl(repo, audit, outboxPublisher);
    }

    @Test
    void scansRealFieldMappingColumns() {
        when(repo.existsByTenantIdAndFqn(eq(TENANT_ID), any())).thenReturn(false);

        int created = service.enqueueScan(TENANT_ID, "ods.customers", List.of(
            Map.of("target", "phone_hash", "targetType", "STRING"),
            Map.of("target", "email_hash", "targetType", "STRING"),
            Map.of("target", "id_card_hash", "targetType", "STRING"),
            Map.of("target", "full_name", "targetType", "STRING"),
            Map.of("target", "age", "targetType", "INT")
        ));

        assertThat(created).isEqualTo(4);
        ArgumentCaptor<PiiScanRecord> captor = ArgumentCaptor.forClass(PiiScanRecord.class);
        verify(repo, org.mockito.Mockito.times(4)).save(captor.capture());
        assertThat(captor.getAllValues())
            .extracting(PiiScanRecord::getFqn)
            .containsExactlyInAnyOrder(
                "ods.customers.phone_hash",
                "ods.customers.email_hash",
                "ods.customers.id_card_hash",
                "ods.customers.full_name"
            );
        assertThat(captor.getAllValues())
            .filteredOn(record -> record.getFqn().endsWith("id_card_hash"))
            .singleElement()
            .extracting(PiiScanRecord::getSuggestLevel)
            .isEqualTo("L4");

        ArgumentCaptor<Map<String, Object>> eventCaptor = ArgumentCaptor.forClass(Map.class);
        verify(outboxPublisher).publish(eq(DomainEvents.SECURITY_PII_DETECTED), eq("ods.customers"), eventCaptor.capture());
        assertThat(eventCaptor.getValue()).containsEntry("tenantId", TENANT_ID.toString());
        assertThat(eventCaptor.getValue()).containsEntry("tableFqn", "ods.customers");
        assertThat(eventCaptor.getValue()).containsEntry("detectionCount", 4);
        assertThat((List<Map<String, Object>>) eventCaptor.getValue().get("detections"))
            .extracting(item -> item.get("column"))
            .containsExactlyInAnyOrder("phone_hash", "email_hash", "id_card_hash", "full_name");
    }

    @Test
    void skipsExistingScanRecords() {
        when(repo.existsByTenantIdAndFqn(TENANT_ID, "ods.customers.phone_hash")).thenReturn(true);

        int created = service.enqueueScan(TENANT_ID, "ods.customers", List.of(
            Map.of("target", "phone_hash", "targetType", "STRING")
        ));

        assertThat(created).isZero();
        verify(repo, never()).save(any());
        verify(outboxPublisher, never()).publish(eq(DomainEvents.SECURITY_PII_DETECTED), any(), any(Map.class));
    }
}
