package com.onelake.modeling.service;

import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.outbox.DomainEvents;
import com.onelake.common.outbox.OutboxPublisher;
import com.onelake.common.util.JsonUtil;
import com.onelake.modeling.domain.entity.DataModel;
import com.onelake.modeling.domain.entity.DataModelColumnMapping;
import com.onelake.modeling.domain.entity.DataModelRun;
import com.onelake.modeling.domain.entity.DataModelSource;
import com.onelake.modeling.dto.DataModelDTO;
import com.onelake.modeling.dto.DwdModelCompileDTO;
import com.onelake.modeling.dto.DwdModelDraftRequest;
import com.onelake.modeling.dto.DwdModelRunDTO;
import com.onelake.modeling.dto.DwdModelRunRequest;
import com.onelake.modeling.repository.DataModelColumnMappingRepository;
import com.onelake.modeling.repository.DataModelRepository;
import com.onelake.modeling.repository.DataModelRunRepository;
import com.onelake.modeling.repository.DataModelSourceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DwdModelServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID = UUID.fromString("d0e42034-2349-474a-ba4e-bc6d2127343d");
    private static final UUID MODEL_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID RUN_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private DataModelRepository modelRepo;
    private DataModelSourceRepository sourceRepo;
    private DataModelColumnMappingRepository mappingRepo;
    private DataModelRunRepository runRepo;
    private DwdModelDagsterClient dagsterClient;
    private DwdRunArtifactReader artifactReader;
    private OutboxPublisher outboxPublisher;
    private JdbcTemplate jdbc;
    private DwdModelService service;
    private final List<DataModelSource> savedSources = new ArrayList<>();
    private final List<DataModelColumnMapping> savedMappings = new ArrayList<>();
    private DataModel savedModel;
    private DataModelRun savedRun;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT_ID);
        TenantContext.setUserId(USER_ID);
        TenantContext.setUsername("dev");
        modelRepo = mock(DataModelRepository.class);
        sourceRepo = mock(DataModelSourceRepository.class);
        mappingRepo = mock(DataModelColumnMappingRepository.class);
        runRepo = mock(DataModelRunRepository.class);
        dagsterClient = mock(DwdModelDagsterClient.class);
        artifactReader = new DwdRunArtifactReader();
        outboxPublisher = mock(OutboxPublisher.class);
        jdbc = mock(JdbcTemplate.class);
        service = new DwdModelService(
            modelRepo,
            sourceRepo,
            mappingRepo,
            runRepo,
            dagsterClient,
            artifactReader,
            outboxPublisher,
            jdbc
        );

        when(modelRepo.findByTenantIdAndTargetFqn(eq(TENANT_ID), anyString())).thenReturn(Optional.empty());
        when(modelRepo.findByIdAndTenantId(eq(MODEL_ID), eq(TENANT_ID))).thenAnswer(invocation -> Optional.ofNullable(savedModel));
        doAnswer(invocation -> {
            DataModel model = invocation.getArgument(0);
            model.setId(MODEL_ID);
            savedModel = model;
            return model;
        }).when(modelRepo).save(any(DataModel.class));
        doAnswer(invocation -> {
            DataModelSource source = invocation.getArgument(0);
            source.setId(UUID.randomUUID());
            savedSources.add(source);
            return source;
        }).when(sourceRepo).save(any(DataModelSource.class));
        doAnswer(invocation -> {
            DataModelColumnMapping mapping = invocation.getArgument(0);
            mapping.setId(UUID.randomUUID());
            savedMappings.add(mapping);
            return mapping;
        }).when(mappingRepo).save(any(DataModelColumnMapping.class));
        doAnswer(invocation -> {
            DataModelRun run = invocation.getArgument(0);
            if (run.getId() == null) {
                run.setId(RUN_ID);
            }
            savedRun = run;
            return run;
        }).when(runRepo).save(any(DataModelRun.class));
        when(runRepo.findByIdAndTenantId(eq(RUN_ID), eq(TENANT_ID))).thenAnswer(invocation -> Optional.ofNullable(savedRun));
        when(runRepo.findByModelIdAndTenantIdOrderByQueuedAtDesc(eq(MODEL_ID), eq(TENANT_ID)))
            .thenAnswer(invocation -> savedRun == null ? List.of() : List.of(savedRun));
        when(sourceRepo.findByModelIdOrderBySortNoAsc(MODEL_ID)).thenAnswer(invocation -> List.copyOf(savedSources));
        when(mappingRepo.findByModelIdOrderBySortNoAsc(MODEL_ID)).thenAnswer(invocation -> List.copyOf(savedMappings));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @SuppressWarnings("unchecked")
    void createDraftBuildsDwdModelFromOdsCatalog() throws Exception {
        mockCatalogAsset("ods.ods_codex_orders", "ODS", "交易", List.of(
            Map.of("name", "order_id", "type", "BIGINT", "classification", "L1"),
            Map.of("name", "user_phone", "type", "VARCHAR", "classification", "L3", "piiType", "PHONE", "suggestLevel", "L3"),
            Map.of("name", "amount", "type", "DECIMAL(18,2)")
        ));

        DataModelDTO dto = service.createDraft(new DwdModelDraftRequest(
            "dwd_trade_order_df",
            "交易",
            "ods.ods_codex_orders",
            "dwd.dwd_trade_order_df",
            "TABLE",
            "order_id",
            null,
            "days(order_time)",
            List.of(
                new DwdModelDraftRequest.ColumnMappingRequest("order_id", "order_id", null, null, null, true, null, null, null, null, null, null),
                new DwdModelDraftRequest.ColumnMappingRequest("user_phone", "user_phone_masked", null, "VARCHAR", "sha256(user_phone)", false, "L3", "PHONE", "L3", null, null, null)
            )
        ));

        assertThat(dto.id()).isEqualTo(MODEL_ID);
        assertThat(dto.layer()).isEqualTo("DWD");
        assertThat(dto.sourceFqn()).isEqualTo("ods.ods_codex_orders");
        assertThat(dto.targetFqn()).isEqualTo("dwd.dwd_trade_order_df");
        assertThat(dto.uniqueKey()).isEqualTo("order_id");
        assertThat(dto.ownerId()).isEqualTo(USER_ID);
        assertThat(dto.ownerName()).isEqualTo("dev");
        assertThat(dto.columnMappings()).hasSize(2);
        assertThat(dto.columnMappings().get(0).primaryKey()).isTrue();
        assertThat(dto.columnMappings().get(1).target()).isEqualTo("user_phone_masked");
        assertThat(dto.compiledSql()).contains("sha256(user_phone) as user_phone_masked");
        assertThat(savedSources).singleElement()
            .satisfies(source -> assertThat(source.getSourceType()).isEqualTo("ODS_TABLE"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void createDraftRejectsNonOdsSource() throws Exception {
        mockCatalogAsset("dwd.dwd_trade_order_df", "DWD", "交易", List.of(
            Map.of("name", "order_id", "type", "BIGINT")
        ));

        assertThatThrownBy(() -> service.createDraft(new DwdModelDraftRequest(
            "dwd_trade_order_detail_df",
            "交易",
            "dwd.dwd_trade_order_df",
            "dwd.dwd_trade_order_detail_df",
            "TABLE",
            null,
            null,
            null,
            null
        )))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("DWD 只能从 ODS 资产派生");
    }

    @Test
    @SuppressWarnings("unchecked")
    void compileArtifactsWritesDbtFilesAndCreatesDisabledDag(@TempDir Path tempDir) throws Exception {
        mockCatalogAsset("ods.ods_codex_orders", "ODS", "交易", List.of(
            Map.of("name", "order_id", "type", "BIGINT"),
            Map.of("name", "status", "type", "VARCHAR"),
            Map.of("name", "order_time", "type", "TIMESTAMP")
        ));
        UUID dagId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        when(jdbc.queryForObject(
            contains("INSERT INTO orchestration.dag"),
            eq(UUID.class),
            eq(TENANT_ID),
            eq("DWD dwd_trade_order_df"),
            eq("onelake_dbt_model_run"),
            anyString()
        )).thenReturn(dagId);
        mockDwdOperatorManifests("output.iceberg_table");

        service.createDraft(new DwdModelDraftRequest(
            "dwd_trade_order_df",
            "交易",
            "ods.ods_codex_orders",
            "dwd.dwd_trade_order_df",
            "TABLE",
            "order_id",
            null,
            "days(order_time)",
            List.of(
                new DwdModelDraftRequest.ColumnMappingRequest("order_id", "order_id", null, null, null, true, null, null, null, null, null, null),
                new DwdModelDraftRequest.ColumnMappingRequest("status", "status", null, null, null, false, null, null, null, null, null, null),
                new DwdModelDraftRequest.ColumnMappingRequest("order_time", "order_time", null, null, null, false, null, null, null, null, null, null)
            )
        ));

        String previous = System.getProperty("onelake.dbt.projectDir");
        System.setProperty("onelake.dbt.projectDir", tempDir.toString());
        try {
            DwdModelCompileDTO compiled = service.compileArtifacts(MODEL_ID);

            assertThat(compiled.orchestrationDagId()).isEqualTo(dagId);
            assertThat(compiled.sqlPath()).isEqualTo("models/intermediate/dwd_trade_order_df.sql");
            assertThat(compiled.engine()).isEqualTo("TRINO_DBT");
            assertThat(compiled.resourceGroup()).isEqualTo("default");
            assertThat(compiled.computeProfile()).isEqualTo("trino-small");
            assertThat(compiled.operatorGraph()).contains("GOVERN", "QUALITY_GATE", "DBT_MODEL");
            assertThat(compiled.operatorGraph())
                .contains("\"operatorRef\":\"input.ods_table\"")
                .contains("\"operatorRef\":\"transform.rename_columns\"")
                .contains("\"operatorRef\":\"gate.not_null\"")
                .contains("\"operatorRef\":\"output.iceberg_table\"")
                .contains("\"tests\":[\"not_null\",\"unique\"]")
                .contains("\"manifest\"")
                .contains("\"compileTarget\":\"SQL_DBT\"");
            assertThat(savedModel.getStatus()).isEqualTo("VALIDATED");
            assertThat(savedModel.getArtifactPath()).isEqualTo("models/intermediate/dwd_trade_order_df.sql");
            assertThat(savedModel.getOrchestrationDagId()).isEqualTo(dagId);
            assertThat(savedModel.getOperatorGraph()).contains("GOVERN");
            assertThat(savedModel.getCostPolicy()).contains("maxScanBytes");

            String modelSql = Files.readString(tempDir.resolve(compiled.sqlPath()));
            String schemaYaml = Files.readString(tempDir.resolve(compiled.schemaPath()));
            String sourceYaml = Files.readString(tempDir.resolve(compiled.sourcePath()));
            assertThat(modelSql).contains("{{ source('ods', 'ods_codex_orders') }}");
            assertThat(modelSql).contains("order_id as order_id");
            assertThat(schemaYaml).contains("name: dwd_trade_order_df");
            assertThat(schemaYaml).contains("- not_null");
            assertThat(schemaYaml).contains("- unique");
            assertThat(sourceYaml).contains("name: ods_codex_orders");
        } finally {
            if (previous == null) {
                System.clearProperty("onelake.dbt.projectDir");
            } else {
                System.setProperty("onelake.dbt.projectDir", previous);
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void compileArtifactsAggregatesSourcesForExistingCompiledDwdModels(@TempDir Path tempDir) throws Exception {
        mockCatalogAsset("ods.ods_codex_orders", "ODS", "交易", List.of(
            Map.of("name", "order_id", "type", "BIGINT"),
            Map.of("name", "status", "type", "VARCHAR")
        ));
        mockCatalogAsset("ods.ods_customers", "ODS", "用户", List.of(
            Map.of("name", "customer_id", "type", "BIGINT"),
            Map.of("name", "customer_name", "type", "VARCHAR")
        ));
        when(jdbc.queryForObject(
            contains("INSERT INTO orchestration.dag"),
            eq(UUID.class),
            eq(TENANT_ID),
            eq("DWD dwd_customer_profile_df"),
            eq("onelake_dbt_model_run"),
            anyString()
        )).thenReturn(UUID.fromString("33333333-3333-3333-3333-333333333333"));
        mockDwdOperatorManifests("output.iceberg_table");

        DataModel existingModel = validatedModel();
        existingModel.setId(UUID.fromString("55555555-5555-5555-5555-555555555555"));
        existingModel.setName("dwd_trade_order_df");
        existingModel.setSourceFqn("ods.ods_codex_orders");
        existingModel.setTargetFqn("dwd.dwd_trade_order_df");
        existingModel.setDbtModelName("dwd_trade_order_df");
        existingModel.setArtifactPath("models/intermediate/dwd_trade_order_df.sql");
        when(modelRepo.findByTenantIdOrderByCreatedAtDesc(TENANT_ID)).thenReturn(List.of(existingModel));

        service.createDraft(new DwdModelDraftRequest(
            "dwd_customer_profile_df",
            "用户",
            "ods.ods_customers",
            "dwd.dwd_customer_profile_df",
            "TABLE",
            "customer_id",
            null,
            null,
            List.of(
                new DwdModelDraftRequest.ColumnMappingRequest("customer_id", "customer_id", null, null, null, true, null, null, null, null, null, null),
                new DwdModelDraftRequest.ColumnMappingRequest("customer_name", "customer_name", null, null, null, false, null, null, null, null, null, null)
            )
        ));

        String previous = System.getProperty("onelake.dbt.projectDir");
        System.setProperty("onelake.dbt.projectDir", tempDir.toString());
        try {
            DwdModelCompileDTO compiled = service.compileArtifacts(MODEL_ID);
            String sourceYaml = Files.readString(tempDir.resolve(compiled.sourcePath()));

            assertThat(sourceYaml)
                .contains("name: ods_codex_orders")
                .contains("name: ods_customers")
                .contains("Source column order_id (BIGINT)")
                .contains("Source column customer_id (BIGINT)");
        } finally {
            if (previous == null) {
                System.clearProperty("onelake.dbt.projectDir");
            } else {
                System.setProperty("onelake.dbt.projectDir", previous);
            }
        }
    }

    @Test
    void generateSchemaYamlMapsQualityGateConfigsToDbtTests() throws Exception {
        DataModel model = validatedModel();
        model.setSourceFqn("ods.ods_codex_orders");
        List<DataModelColumnMapping> mappings = List.of(
            columnMapping("status", "status"),
            columnMapping("customer_id", "customer_id"),
            columnMapping("amount", "amount"),
            columnMapping("phone", "phone")
        );
        Map<String, Object> graph = Map.of("nodes", List.of(
            Map.of(
                "id", "status_enum_gate",
                "nodeType", "QUALITY_GATE",
                "operatorRef", "gate.enum",
                "config", Map.of(
                    "column", "status",
                    "values", List.of("PAID", "CANCEL")
                )
            ),
            Map.of(
                "id", "customer_ref_gate",
                "nodeType", "QUALITY_GATE",
                "operatorRef", "gate.referential",
                "config", Map.of(
                    "column", "customer_id",
                    "refModel", "dim_customer",
                    "refColumn", "id"
                )
            ),
            Map.of(
                "id", "amount_range_gate",
                "nodeType", "QUALITY_GATE",
                "operatorRef", "gate.range",
                "config", Map.of(
                    "column", "amount",
                    "min", 0,
                    "max", 9999
                )
            ),
            Map.of(
                "id", "phone_regex_gate",
                "nodeType", "QUALITY_GATE",
                "operatorRef", "gate.regex",
                "config", Map.of(
                    "column", "phone",
                    "pattern", "^1[3-9][0-9]{9}$"
                )
            ),
            Map.of(
                "id", "row_count_gate",
                "nodeType", "QUALITY_GATE",
                "operatorRef", "gate.row_count",
                "config", Map.of(
                    "min", 1,
                    "max", 100000
                )
            ),
            Map.of(
                "id", "custom_sql_gate",
                "nodeType", "QUALITY_GATE",
                "operatorRef", "gate.custom_sql",
                "config", Map.of(
                    "assertionSql", "select * from {{ model }} where amount < 0"
                )
            )
        ));

        String schemaYaml = generateSchemaYaml(model, mappings, "dwd_trade_order_df", graph);

        assertThat(schemaYaml)
            .contains("    tests:\n      - onelake_row_count:")
            .contains("            min_value: 1")
            .contains("            max_value: 100000")
            .contains("      - onelake_custom_sql:")
            .contains("            assertion_sql: \"select * from __ONELAKE_MODEL__ where amount < 0\"")
            .contains("      - name: status")
            .contains("          - accepted_values:")
            .contains("              arguments:")
            .contains("                values:")
            .contains("                  - \"PAID\"")
            .contains("                  - \"CANCEL\"")
            .contains("      - name: customer_id")
            .contains("          - relationships:")
            .contains("                to: ref('dim_customer')")
            .contains("                field: id")
            .contains("      - name: amount")
            .contains("          - onelake_range:")
            .contains("                min_value: 0")
            .contains("                max_value: 9999")
            .contains("      - name: phone")
            .contains("          - onelake_regex:")
            .contains("                pattern: \"^1[3-9][0-9]{9}$\"");
    }

    @Test
    @SuppressWarnings("unchecked")
    void compileArtifactsWritesSourceFreshnessForExistingCompiledDwdModels(@TempDir Path tempDir) throws Exception {
        mockCatalogAsset("ods.ods_codex_orders", "ODS", "交易", List.of(
            Map.of("name", "order_id", "type", "BIGINT"),
            Map.of("name", "updated_at", "type", "TIMESTAMP")
        ));
        mockCatalogAsset("ods.ods_customers", "ODS", "用户", List.of(
            Map.of("name", "customer_id", "type", "BIGINT"),
            Map.of("name", "customer_name", "type", "VARCHAR")
        ));
        when(jdbc.queryForObject(
            contains("INSERT INTO orchestration.dag"),
            eq(UUID.class),
            eq(TENANT_ID),
            eq("DWD dwd_customer_freshness_df"),
            eq("onelake_dbt_model_run"),
            anyString()
        )).thenReturn(UUID.fromString("33333333-3333-3333-3333-333333333333"));
        mockDwdOperatorManifests("output.iceberg_table");

        DataModel existingModel = validatedModel();
        existingModel.setId(UUID.fromString("66666666-6666-6666-6666-666666666666"));
        existingModel.setSourceFqn("ods.ods_codex_orders");
        existingModel.setOperatorGraph(JsonUtil.toJson(Map.of("nodes", List.of(Map.of(
            "id", "freshness_gate",
            "nodeType", "QUALITY_GATE",
            "operatorRef", "gate.freshness",
            "config", Map.of(
                "sourceFqn", "ods.ods_codex_orders",
                "column", "updated_at",
                "maxDelay", "24h",
                "actionOnViolation", "WARN"
            )
        )))));
        when(modelRepo.findByTenantIdOrderByCreatedAtDesc(TENANT_ID)).thenReturn(List.of(existingModel));

        service.createDraft(new DwdModelDraftRequest(
            "dwd_customer_freshness_df",
            "用户",
            "ods.ods_customers",
            "dwd.dwd_customer_freshness_df",
            "TABLE",
            "customer_id",
            null,
            null,
            List.of(
                new DwdModelDraftRequest.ColumnMappingRequest("customer_id", "customer_id", null, null, null, true, null, null, null, null, null, null),
                new DwdModelDraftRequest.ColumnMappingRequest("customer_name", "customer_name", null, null, null, false, null, null, null, null, null, null)
            )
        ));

        String previous = System.getProperty("onelake.dbt.projectDir");
        System.setProperty("onelake.dbt.projectDir", tempDir.toString());
        try {
            DwdModelCompileDTO compiled = service.compileArtifacts(MODEL_ID);
            String sourceYaml = Files.readString(tempDir.resolve(compiled.sourcePath()));

            assertThat(sourceYaml)
                .contains("      - name: ods_codex_orders")
                .contains("        loaded_at_field: \"updated_at\"")
                .contains("        freshness:\n          warn_after:\n            count: 24\n            period: hour")
                .doesNotContain("          error_after:");
        } finally {
            if (previous == null) {
                System.clearProperty("onelake.dbt.projectDir");
            } else {
                System.setProperty("onelake.dbt.projectDir", previous);
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void compileArtifactsPreservesStoredCustomSqlGate(@TempDir Path tempDir) throws Exception {
        mockCatalogAsset("ods.ods_codex_orders", "ODS", "交易", List.of(
            Map.of("name", "order_id", "type", "BIGINT"),
            Map.of("name", "amount", "type", "DECIMAL(18,2)")
        ));
        when(jdbc.queryForObject(
            contains("INSERT INTO orchestration.dag"),
            eq(UUID.class),
            eq(TENANT_ID),
            eq("DWD dwd_trade_custom_sql_df"),
            eq("onelake_dbt_model_run"),
            anyString()
        )).thenReturn(UUID.fromString("33333333-3333-3333-3333-333333333333"));
        mockDwdOperatorManifests("output.iceberg_table");

        service.createDraft(new DwdModelDraftRequest(
            "dwd_trade_custom_sql_df",
            "交易",
            "ods.ods_codex_orders",
            "dwd.dwd_trade_custom_sql_df",
            "TABLE",
            "order_id",
            null,
            null,
            List.of(
                new DwdModelDraftRequest.ColumnMappingRequest("order_id", "order_id", null, null, null, true, null, null, null, null, null, null),
                new DwdModelDraftRequest.ColumnMappingRequest("amount", "amount", null, null, null, false, null, null, null, null, null, null)
            )
        ));
        savedModel.setOperatorGraph(JsonUtil.toJson(Map.of("nodes", List.of(Map.of(
            "id", "custom_sql_gate",
            "nodeType", "QUALITY_GATE",
            "operatorRef", "gate.custom_sql",
            "config", Map.of("assertionSql", "select * from {{ model }} where amount < 0")
        )))));

        String previous = System.getProperty("onelake.dbt.projectDir");
        System.setProperty("onelake.dbt.projectDir", tempDir.toString());
        try {
            DwdModelCompileDTO compiled = service.compileArtifacts(MODEL_ID);
            String schemaYaml = Files.readString(tempDir.resolve(compiled.schemaPath()));

            assertThat(compiled.operatorGraph()).contains("gate.custom_sql", "custom_sql_gate");
            assertThat(schemaYaml)
                .contains("      - onelake_custom_sql:")
                .contains("            assertion_sql: \"select * from __ONELAKE_MODEL__ where amount < 0\"");
        } finally {
            if (previous == null) {
                System.clearProperty("onelake.dbt.projectDir");
            } else {
                System.setProperty("onelake.dbt.projectDir", previous);
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void compileIncrementalModelWritesWatermarkFilter(@TempDir Path tempDir) throws Exception {
        mockCatalogAsset("ods.ods_codex_orders", "ODS", "交易", List.of(
            Map.of("name", "order_id", "type", "BIGINT"),
            Map.of("name", "updated_at", "type", "TIMESTAMP")
        ));
        when(jdbc.queryForObject(
            contains("INSERT INTO orchestration.dag"),
            eq(UUID.class),
            eq(TENANT_ID),
            eq("DWD dwd_trade_order_df"),
            eq("onelake_dbt_model_run"),
            anyString()
        )).thenReturn(UUID.fromString("33333333-3333-3333-3333-333333333333"));
        mockDwdOperatorManifests("output.incremental_merge");

        service.createDraft(new DwdModelDraftRequest(
            "dwd_trade_order_df",
            "交易",
            "ods.ods_codex_orders",
            "dwd.dwd_trade_order_df",
            "INCREMENTAL",
            "order_id",
            "updated_at",
            null,
            List.of(
                new DwdModelDraftRequest.ColumnMappingRequest("order_id", "order_id", null, null, null, true, null, null, null, null, null, null),
                new DwdModelDraftRequest.ColumnMappingRequest("updated_at", "updated_at", null, null, null, false, null, null, null, null, null, null)
            )
        ));

        String previous = System.getProperty("onelake.dbt.projectDir");
        System.setProperty("onelake.dbt.projectDir", tempDir.toString());
        try {
            DwdModelCompileDTO compiled = service.compileArtifacts(MODEL_ID);
            String modelSql = Files.readString(tempDir.resolve(compiled.sqlPath()));

            assertThat(modelSql).contains("materialized='incremental'");
            assertThat(modelSql).contains("unique_key='order_id'");
            assertThat(modelSql).contains("incremental_strategy='merge'");
            assertThat(modelSql).contains("{% if is_incremental() %}");
            assertThat(modelSql).contains("where updated_at > (select coalesce(max(updated_at), timestamp '1970-01-01 00:00:00') from {{ this }})");
        } finally {
            if (previous == null) {
                System.clearProperty("onelake.dbt.projectDir");
            } else {
                System.setProperty("onelake.dbt.projectDir", previous);
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void compileArtifactsFailsWhenDefaultOperatorManifestIsMissing() throws Exception {
        mockCatalogAsset("ods.ods_codex_orders", "ODS", "交易", List.of(
            Map.of("name", "order_id", "type", "BIGINT")
        ));
        when(jdbc.queryForObject(
            contains("FROM orchestration.operator"),
            any(RowMapper.class),
            eq("input.ods_table")
        )).thenThrow(new EmptyResultDataAccessException(1));

        service.createDraft(new DwdModelDraftRequest(
            "dwd_trade_order_df",
            "交易",
            "ods.ods_codex_orders",
            "dwd.dwd_trade_order_df",
            "TABLE",
            "order_id",
            null,
            null,
            List.of(
                new DwdModelDraftRequest.ColumnMappingRequest("order_id", "order_id", null, null, null, true, null, null, null, null, null, null)
            )
        ));

        assertThatThrownBy(() -> service.compileArtifacts(MODEL_ID))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("DWD 默认算子 Manifest 不存在")
            .hasMessageContaining("input.ods_table");
    }

    @Test
    void runValidatedModelLaunchesDagsterAndStoresRun() {
        savedModel = validatedModel();
        when(dagsterClient.launchDwdModelRun(eq("onelake_dbt_model_run"), anyMap(), anyList()))
            .thenReturn(new DwdModelDagsterClient.LaunchResult("dagster-run-1", "STARTED"));

        DwdModelRunDTO dto = service.run(MODEL_ID, new DwdModelRunRequest("MANUAL", null));

        assertThat(dto.id()).isEqualTo(RUN_ID);
        assertThat(dto.status()).isEqualTo("RUNNING");
        assertThat(dto.dagsterRunId()).isEqualTo("dagster-run-1");
        assertThat(dto.resourceGroup()).isEqualTo("default");
        assertThat(dto.computeProfile()).isEqualTo("trino-small");
        assertThat(savedModel.getLastRunId()).isEqualTo(RUN_ID);
    }

    @Test
    @SuppressWarnings("unchecked")
    void backfillRunPassesBackfillConfigToDagster() {
        savedModel = validatedModel();
        when(dagsterClient.launchDwdModelRun(eq("onelake_dbt_model_run"), anyMap(), anyList()))
            .thenReturn(new DwdModelDagsterClient.LaunchResult("dagster-run-1", "STARTED"));
        UUID sourceRunId = UUID.fromString("55555555-5555-5555-5555-555555555555");
        ArgumentCaptor<Map<String, Object>> configCaptor = ArgumentCaptor.forClass(Map.class);

        DwdModelRunDTO dto = service.run(MODEL_ID, new DwdModelRunRequest(
            "BACKFILL",
            sourceRunId,
            true,
            "2026-06-01",
            "2026-06-07"
        ));

        verify(dagsterClient).launchDwdModelRun(eq("onelake_dbt_model_run"), configCaptor.capture(), anyList());
        Map<String, Object> ops = (Map<String, Object>) configCaptor.getValue().get("ops");
        Map<String, Object> op = (Map<String, Object>) ops.get("run_dwd_model");
        Map<String, Object> config = (Map<String, Object>) op.get("config");
        Map<String, Object> backfill = (Map<String, Object>) config.get("backfill");
        assertThat(dto.triggerType()).isEqualTo("BACKFILL");
        assertThat(savedRun.getQueueReason()).contains("fullRefresh=true", "2026-06-01..2026-06-07", sourceRunId.toString());
        assertThat(backfill)
            .containsEntry("enabled", true)
            .containsEntry("fullRefresh", true)
            .containsEntry("partitionStart", "2026-06-01")
            .containsEntry("partitionEnd", "2026-06-07")
            .containsEntry("sourceIntegrationRunId", sourceRunId.toString());
    }

    @Test
    void listModelsBySourceFqnReturnsDwdModels() {
        savedModel = validatedModel();
        when(modelRepo.findByTenantIdAndSourceFqnOrderByCreatedAtDesc(TENANT_ID, "ods.ods_codex_orders"))
            .thenReturn(List.of(savedModel));

        List<DataModelDTO> models = service.list("ods.ods_codex_orders", null);

        assertThat(models).singleElement().satisfies(model -> {
            assertThat(model.id()).isEqualTo(MODEL_ID);
            assertThat(model.sourceFqn()).isEqualTo("ods.ods_codex_orders");
            assertThat(model.targetFqn()).isEqualTo("dwd.dwd_trade_order_df");
        });
    }

    @Test
    void getRunRefreshesDagsterTerminalStatusAndPublishesLoadedEvent(@TempDir Path tempDir) throws Exception {
        savedModel = validatedModel();
        savedRun = new DataModelRun();
        savedRun.setId(RUN_ID);
        savedRun.setTenantId(TENANT_ID);
        savedRun.setModelId(MODEL_ID);
        savedRun.setStatus("RUNNING");
        savedRun.setDagsterRunId("dagster-run-1");
        Instant startedAt = Instant.parse("2026-06-22T01:00:00Z");
        Instant finishedAt = Instant.parse("2026-06-22T01:03:00Z");
        when(dagsterClient.getRunStatus("dagster-run-1"))
            .thenReturn(new DwdModelDagsterClient.RunStatus("dagster-run-1", "SUCCESS", startedAt, finishedAt));

        Files.createDirectories(tempDir.resolve("target"));
        Files.writeString(tempDir.resolve("target/run_results.json"), """
            {"results":[
              {"unique_id":"model.onelake.dwd_trade_order_df","status":"success","adapter_response":{"rows_affected":8}},
              {"unique_id":"test.onelake.not_null_dwd_trade_order_df_order_id.abc","status":"pass","adapter_response":{"rows_affected":-1},"failures":0}
            ]}
            """);
        String previous = System.getProperty("onelake.dbt.projectDir");
        System.setProperty("onelake.dbt.projectDir", tempDir.toString());
        DwdModelRunDTO dto;
        try {
            dto = service.getRun(RUN_ID);
        } finally {
            if (previous == null) {
                System.clearProperty("onelake.dbt.projectDir");
            } else {
                System.setProperty("onelake.dbt.projectDir", previous);
            }
        }

        assertThat(dto.status()).isEqualTo("SUCCEEDED");
        assertThat(dto.startedAt()).isEqualTo(startedAt);
        assertThat(dto.finishedAt()).isEqualTo(finishedAt);
        assertThat(dto.rowsWritten()).isEqualTo(8L);
        assertThat(dto.artifactsPath()).isEqualTo("target/run_results.json");

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(outboxPublisher).publish(
            eq(DomainEvents.MODELING_MODEL_LOADED),
            eq(RUN_ID.toString()),
            payloadCaptor.capture()
        );
        assertThat(payloadCaptor.getValue())
            .containsEntry("modelId", MODEL_ID.toString())
            .containsEntry("targetFqn", "dwd.dwd_trade_order_df")
            .containsEntry("ownerId", USER_ID.toString())
            .containsEntry("ownerName", "dev")
            .containsEntry("rowsWritten", 8L);
        assertThat((List<Map<String, Object>>) payloadCaptor.getValue().get("qualityChecks"))
            .singleElement()
            .satisfies(check -> {
                assertThat(check).containsEntry("status", "pass");
                assertThat(check.get("uniqueId").toString()).startsWith("test.onelake.not_null");
            });
    }

    @Test
    void refreshByDagsterRunIdUsesExistingDwdTerminalTail() {
        savedModel = validatedModel();
        savedRun = new DataModelRun();
        savedRun.setId(RUN_ID);
        savedRun.setTenantId(TENANT_ID);
        savedRun.setModelId(MODEL_ID);
        savedRun.setStatus("RUNNING");
        savedRun.setDagsterRunId("dagster-run-1");
        Instant startedAt = Instant.parse("2026-06-22T01:00:00Z");
        Instant finishedAt = Instant.parse("2026-06-22T01:03:00Z");
        when(runRepo.findByDagsterRunIdAndTenantId("dagster-run-1", TENANT_ID)).thenReturn(List.of(savedRun));
        when(dagsterClient.getRunStatus("dagster-run-1"))
            .thenReturn(new DwdModelDagsterClient.RunStatus("dagster-run-1", "SUCCESS", startedAt, finishedAt));

        boolean refreshed = service.refreshByDagsterRunId("dagster-run-1");

        assertThat(refreshed).isTrue();
        assertThat(savedRun.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(savedRun.getStartedAt()).isEqualTo(startedAt);
        assertThat(savedRun.getFinishedAt()).isEqualTo(finishedAt);
        verify(outboxPublisher).publish(eq(DomainEvents.MODELING_MODEL_LOADED), eq(RUN_ID.toString()), any(Map.class));
    }

    @Test
    void runLaunchFailureStoresFailedRunAndPublishesFailedEvent() {
        savedModel = validatedModel();
        doThrow(new RuntimeException("dagster unavailable"))
            .when(dagsterClient).launchDwdModelRun(eq("onelake_dbt_model_run"), anyMap(), anyList());

        DwdModelRunDTO dto = service.run(MODEL_ID, new DwdModelRunRequest("MANUAL", null));

        assertThat(dto.status()).isEqualTo("FAILED");
        assertThat(dto.errorMsg()).contains("dagster unavailable");
        verify(outboxPublisher).publish(
            eq(DomainEvents.MODELING_MODEL_FAILED),
            eq(RUN_ID.toString()),
            any(Map.class)
        );
    }

    private void mockCatalogAsset(String fqn, String layer, String domain, List<Map<String, Object>> columns)
        throws Exception {
        doAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getString("om_fqn")).thenReturn(fqn);
            when(rs.getString("layer")).thenReturn(layer);
            when(rs.getString("domain")).thenReturn(domain);
            when(rs.getString("columns")).thenReturn(JsonUtil.toJson(columns));
            return mapper.mapRow(rs, 0);
        }).when(jdbc).queryForObject(
            anyString(),
            any(RowMapper.class),
            eq(TENANT_ID),
            eq(fqn)
        );
    }

    private void mockDwdOperatorManifests(String outputRef) throws Exception {
        mockBuiltInOperator("input.ods_table", "INPUT");
        mockBuiltInOperator("transform.rename_columns", "TRANSFORM");
        mockBuiltInOperator("govern.drop_required_missing", "GOVERN");
        mockBuiltInOperator("gate.not_null", "QUALITY_GATE");
        mockBuiltInOperator(outputRef, "OUTPUT");
    }

    private void mockBuiltInOperator(String operatorRef, String category) throws Exception {
        doAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getString("operator_ref")).thenReturn(operatorRef);
            when(rs.getString("version")).thenReturn("1.0.0");
            when(rs.getString("category")).thenReturn(category);
            when(rs.getString("manifest")).thenReturn(operatorManifest(operatorRef, category));
            return mapper.mapRow(rs, 0);
        }).when(jdbc).queryForObject(
            contains("FROM orchestration.operator"),
            any(RowMapper.class),
            eq(operatorRef)
        );
    }

    private String operatorManifest(String operatorRef, String category) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("operatorRef", operatorRef);
        manifest.put("version", "1.0.0");
        manifest.put("category", category);
        manifest.put("scope", "BUILTIN");
        manifest.put("displayName", operatorRef);
        manifest.put("description", "test manifest");
        manifest.put("inputPorts", List.of(Map.of("name", "in", "cardinality", "ONE", "accept", "TABLE")));
        manifest.put("outputSchema", Map.of("mode", "PASSTHROUGH"));
        manifest.put("paramsSchema", Map.of("type", "object"));
        manifest.put("compileTarget", "SQL_DBT");
        manifest.put("template", Map.of("kind", "TEST", "sql", "select 1"));
        manifest.put("lineageRule", Map.of("type", "ONE_TO_ONE"));
        manifest.put("emitsQualityResult", "QUALITY_GATE".equals(category));
        manifest.put("policy", Map.of("actionOnViolation", "QUALITY_GATE".equals(category) ? "FAIL" : "WARN"));
        return JsonUtil.toJson(manifest);
    }

    private String generateSchemaYaml(
        DataModel model,
        List<DataModelColumnMapping> mappings,
        String dbtModelName,
        Map<String, Object> graph
    ) throws Exception {
        Method method = DwdModelService.class.getDeclaredMethod(
            "generateSchemaYaml",
            DataModel.class,
            List.class,
            String.class,
            Map.class
        );
        method.setAccessible(true);
        return (String) method.invoke(service, model, mappings, dbtModelName, graph);
    }

    private DataModelColumnMapping columnMapping(String source, String target) {
        DataModelColumnMapping mapping = new DataModelColumnMapping();
        mapping.setSourceColumn(source);
        mapping.setTargetColumn(target);
        mapping.setPrimaryKey(false);
        return mapping;
    }

    private DataModel validatedModel() {
        DataModel model = new DataModel();
        model.setId(MODEL_ID);
        model.setTenantId(TENANT_ID);
        model.setName("dwd_trade_order_df");
        model.setLayer("DWD");
        model.setDomain("交易");
        model.setSourceFqn("ods.ods_codex_orders");
        model.setTargetFqn("dwd.dwd_trade_order_df");
        model.setStatus("VALIDATED");
        model.setMaterialization("TABLE");
        model.setDbtModelName("dwd_trade_order_df");
        model.setDagsterJob("onelake_dbt_model_run");
        model.setArtifactPath("models/intermediate/dwd_trade_order_df.sql");
        model.setOrchestrationDagId(UUID.fromString("33333333-3333-3333-3333-333333333333"));
        model.setResourceGroup("default");
        model.setComputeProfile("trino-small");
        model.setEngine("TRINO_DBT");
        model.setOwnerId(USER_ID);
        model.setOwnerName("dev");
        return model;
    }
}
