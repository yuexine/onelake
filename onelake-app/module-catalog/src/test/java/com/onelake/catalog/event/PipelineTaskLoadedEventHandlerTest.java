package com.onelake.catalog.event;

import com.onelake.catalog.config.TrinoConnectionFactory;
import com.onelake.catalog.domain.entity.Asset;
import com.onelake.catalog.domain.entity.LineageEdge;
import com.onelake.catalog.repository.AssetRepository;
import com.onelake.catalog.repository.LineageEdgeRepository;
import com.onelake.common.outbox.DomainEvents;
import com.onelake.common.outbox.OutboxEvent;
import com.onelake.common.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PipelineTaskLoadedEventHandlerTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private AssetRepository assetRepo;
    private LineageEdgeRepository lineageRepo;
    private PipelineTaskLoadedEventHandler handler;

    @BeforeEach
    void setUp() {
        assetRepo = mock(AssetRepository.class);
        lineageRepo = mock(LineageEdgeRepository.class);
        TrinoConnectionFactory trinoConnectionFactory = mock(TrinoConnectionFactory.class);
        handler = new PipelineTaskLoadedEventHandler(assetRepo, lineageRepo, trinoConnectionFactory);
        when(assetRepo.findByTenantIdAndOmFqn(any(), any())).thenReturn(Optional.empty());
        when(assetRepo.save(any(Asset.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(lineageRepo.findByTenantIdAndUpstreamFqnAndDownstreamFqn(any(), any(), any()))
            .thenReturn(Optional.empty());
    }

    @Test
    void registersSparkTargetQualityTableAndLineage() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tenantId", TENANT_ID.toString());
        payload.put("ownerId", OWNER_ID.toString());
        payload.put("ownerName", "dev");
        payload.put("taskKey", "spark_user_governance");
        payload.put("taskName", "Spark 用户字段治理");
        payload.put("taskType", "PYSPARK");
        payload.put("engine", "PYSPARK");
        payload.put("targetFqn", "iceberg.dwd.user");
        payload.put("runId", UUID.randomUUID().toString());
        payload.put("fromTables", List.of("onelake.ods.user"));
        payload.put("catalog", Map.of(
            "description", "Spark 用户字段治理结果表",
            "domain", "用户",
            "classification", "L4",
            "qualityScore", 100,
            "tags", List.of("governed", "pii"),
            "columns", List.of(
                column("user_name", "VARCHAR"),
                column("phone", "VARCHAR"),
                column("id_card", "VARCHAR"),
                column("user_desc", "VARCHAR"),
                column("user_uuid", "VARCHAR")
            ),
            "qualityTables", List.of(Map.of(
                "fqn", "dwd.user_quality_check",
                "columns", List.of(
                    column("check_name", "VARCHAR"),
                    column("passed", "BOOLEAN"),
                    column("actual_value", "BIGINT"),
                    column("expected_value", "BIGINT")
                )
            ))
        ));
        handler.handle(event(payload));

        org.mockito.ArgumentCaptor<Asset> assetCaptor = org.mockito.ArgumentCaptor.forClass(Asset.class);
        verify(assetRepo, org.mockito.Mockito.times(2)).save(assetCaptor.capture());

        Asset target = assetCaptor.getAllValues().get(0);
        assertThat(target.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(target.getOmFqn()).isEqualTo("dwd.user");
        assertThat(target.getLayer()).isEqualTo("DWD");
        assertThat(target.getDisplayName()).isEqualTo("user");
        assertThat(target.getDomain()).isEqualTo("用户");
        assertThat(target.getOwnerId()).isEqualTo(OWNER_ID);
        assertThat(target.getClassification()).isEqualTo("L4");
        assertThat(target.getQualityScore()).isEqualByComparingTo("100");
        assertThat(target.getTags()).contains("pipeline", "spark", "governed");
        assertThat(target.getColumns()).contains(
            "\"name\":\"phone\"",
            "\"piiType\":\"手机号\"",
            "\"name\":\"id_card\"",
            "\"piiType\":\"身份证\"",
            "\"name\":\"user_uuid\"",
            "\"description\":\"用户 UUID\""
        );

        Asset quality = assetCaptor.getAllValues().get(1);
        assertThat(quality.getOmFqn()).isEqualTo("dwd.user_quality_check");
        assertThat(quality.getTags()).contains("quality");
        assertThat(quality.getQualityScore()).isEqualByComparingTo("100");

        org.mockito.ArgumentCaptor<LineageEdge> lineageCaptor = org.mockito.ArgumentCaptor.forClass(LineageEdge.class);
        verify(lineageRepo).save(lineageCaptor.capture());
        assertThat(lineageCaptor.getValue().getUpstreamFqn()).isEqualTo("ods.user");
        assertThat(lineageCaptor.getValue().getDownstreamFqn()).isEqualTo("dwd.user");
    }

    private Map<String, Object> column(String name, String type) {
        return Map.of("name", name, "type", type);
    }

    private OutboxEvent event(Map<String, Object> payload) {
        OutboxEvent event = new OutboxEvent();
        event.setId(UUID.randomUUID());
        event.setTenantId(TENANT_ID);
        event.setEventType(DomainEvents.PIPELINE_TASK_LOADED);
        event.setAggregateId("pipeline");
        event.setPayload(JsonUtil.toJson(payload));
        return event;
    }
}
