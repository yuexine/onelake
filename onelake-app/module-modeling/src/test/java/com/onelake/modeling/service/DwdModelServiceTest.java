package com.onelake.modeling.service;

import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.outbox.DomainEvents;
import com.onelake.common.outbox.OutboxPublisher;
import com.onelake.common.util.JsonUtil;
import com.onelake.modeling.domain.entity.DataModel;
import com.onelake.modeling.domain.entity.DataModelColumnMapping;
import com.onelake.modeling.domain.entity.DataModelSource;
import com.onelake.modeling.dto.DataModelDTO;
import com.onelake.modeling.dto.DwdModelCompileDTO;
import com.onelake.modeling.dto.DwdModelDraftRequest;
import com.onelake.modeling.repository.DataModelColumnMappingRepository;
import com.onelake.modeling.repository.DataModelRepository;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DwdModelServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID = UUID.fromString("d0e42034-2349-474a-ba4e-bc6d2127343d");
    private static final UUID MODEL_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private DataModelRepository modelRepo;
    private DataModelSourceRepository sourceRepo;
    private DataModelColumnMappingRepository mappingRepo;
    private OutboxPublisher outboxPublisher;
    private JdbcTemplate jdbc;
    private DwdModelService service;
    private final List<DataModelSource> savedSources = new ArrayList<>();
    private final List<DataModelColumnMapping> savedMappings = new ArrayList<>();
    private DataModel savedModel;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT_ID);
        TenantContext.setUserId(USER_ID);
        TenantContext.setUsername("dev");
        modelRepo = mock(DataModelRepository.class);
        sourceRepo = mock(DataModelSourceRepository.class);
        mappingRepo = mock(DataModelColumnMappingRepository.class);
        outboxPublisher = mock(OutboxPublisher.class);
        jdbc = mock(JdbcTemplate.class);
        service = new DwdModelService(
            modelRepo,
            sourceRepo,
            mappingRepo,
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
            eq("onelake_pipeline_run"),
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
            assertThat(compiled.engine()).isEqualTo("SPARK");
            assertThat(compiled.resourceGroup()).isEqualTo("spark-default");
            assertThat(compiled.computeProfile()).isEqualTo("spark-small");
            assertThat(compiled.operatorGraph()).contains("GOVERN", "QUALITY_GATE", "transform.spark_sql");
            assertThat(compiled.operatorGraph())
                .contains("\"operatorRef\":\"input.ods_table\"")
                .contains("\"operatorRef\":\"transform.rename_columns\"")
                .contains("\"operatorRef\":\"gate.not_null\"")
                .contains("\"operatorRef\":\"transform.spark_sql\"")
                .contains("\"operatorRef\":\"output.iceberg_table\"")
                .contains("\"tests\":[\"not_null\",\"unique\"]")
                .contains("\"manifest\"")
                .contains("\"compileTarget\":\"SPARK\"");
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
    void compileArtifactsRendersGovernanceLookupJoin(@TempDir Path tempDir) throws Exception {
        mockCatalogAsset("ods.ods_codex_orders", "ODS", "交易", List.of(
            Map.of("name", "order_id", "type", "BIGINT"),
            Map.of("name", "customer_id", "type", "BIGINT")
        ));
        mockCatalogAsset("ods.dim_customer", "ODS", "客户", List.of(
            Map.of("name", "customer_id", "type", "BIGINT"),
            Map.of("name", "customer_name", "type", "VARCHAR")
        ));
        when(jdbc.queryForObject(
            contains("INSERT INTO orchestration.dag"),
            eq(UUID.class),
            eq(TENANT_ID),
            eq("DWD dwd_trade_order_customer_df"),
            eq("onelake_pipeline_run"),
            anyString()
        )).thenReturn(UUID.fromString("33333333-3333-3333-3333-333333333333"));
        mockDwdOperatorManifests("output.iceberg_table");

        String operatorGraph = JsonUtil.toJson(Map.of(
            "version", 1,
            "pipelineMode", "SPARK_GOVERNANCE",
            "nodes", List.of(Map.of(
                "id", "lookup_customer",
                "type", "TRANSFORM",
                "nodeType", "TRANSFORM",
                "operatorRef", "join.lookup_enrich",
                "config", Map.of(
                    "type", "LOOKUP_JOIN",
                    "lookupFqn", "ods.dim_customer",
                    "alias", "lk_customer",
                    "leftKey", "customer_id",
                    "rightKey", "customer_id",
                    "fields", List.of(Map.of("source", "customer_name", "target", "customer_name"))
                )
            )),
            "edges", List.of(Map.of("source", "transform_mapping", "target", "lookup_customer"))
        ));

        service.createDraft(new DwdModelDraftRequest(
            "dwd_trade_order_customer_df",
            "交易",
            "ods.ods_codex_orders",
            "dwd.dwd_trade_order_customer_df",
            "TABLE",
            "order_id",
            null,
            null,
            List.of(
                new DwdModelDraftRequest.ColumnMappingRequest("order_id", "order_id", null, null, null, true, null, null, null, null, null, null),
                new DwdModelDraftRequest.ColumnMappingRequest("customer_id", "customer_id", null, null, null, false, null, null, null, null, null, null),
                new DwdModelDraftRequest.ColumnMappingRequest("customer_id", "customer_name", "BIGINT", "VARCHAR", "lk_customer.customer_name", false, null, null, null, null, null, null)
            ),
            "SPARK_GOVERNANCE",
            1,
            operatorGraph,
            "spark-default",
            "spark-small",
            "SPARK",
            null
        ));

        String previous = System.getProperty("onelake.dbt.projectDir");
        System.setProperty("onelake.dbt.projectDir", tempDir.toString());
        try {
            DwdModelCompileDTO compiled = service.compileArtifacts(MODEL_ID);
            String modelSql = Files.readString(tempDir.resolve(compiled.sqlPath()));
            String sourceYaml = Files.readString(tempDir.resolve(compiled.sourcePath()));

            assertThat(modelSql)
                .contains("lk_customer.customer_name as customer_name")
                .contains("left join {{ source('ods', 'dim_customer') }} as lk_customer")
                .contains("src.customer_id = lk_customer.customer_id");
            assertThat(sourceYaml).contains("name: dim_customer");
            assertThat(compiled.operatorGraph()).contains("join.lookup_enrich", "lookup_customer");
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
    void compileArtifactsRendersPublishedCodebookMapping(@TempDir Path tempDir) throws Exception {
        mockCatalogAsset("ods.ods_codex_orders", "ODS", "交易", List.of(
            Map.of("name", "order_id", "type", "BIGINT"),
            Map.of("name", "gender", "type", "VARCHAR")
        ));
        when(jdbc.queryForObject(
            contains("FROM modeling.codebook cb"),
            eq(String.class),
            eq(TENANT_ID),
            eq("core.gender"),
            eq("2026.06")
        )).thenReturn("""
            [
              {"from": "M", "to": "男", "label": "男性"},
              {"from": "F", "to": "女", "label": "女性"}
            ]
            """);
        when(jdbc.queryForObject(
            contains("INSERT INTO orchestration.dag"),
            eq(UUID.class),
            eq(TENANT_ID),
            eq("DWD dwd_trade_order_gender_df"),
            eq("onelake_pipeline_run"),
            anyString()
        )).thenReturn(UUID.fromString("33333333-3333-3333-3333-333333333333"));
        mockDwdOperatorManifests("output.iceberg_table");

        String operatorGraph = JsonUtil.toJson(Map.of(
            "version", 1,
            "pipelineMode", "SPARK_GOVERNANCE",
            "nodes", List.of(Map.of(
                "id", "dict_gender",
                "type", "GOVERN",
                "nodeType", "GOVERN",
                "operatorRef", "standard.codebook_mapping",
                "config", Map.of(
                    "type", "DICTIONARY_MAPPING",
                    "column", "gender",
                    "outputColumn", "gender_name",
                    "dictionaryRef", "core.gender",
                    "dictionaryVersion", "2026.06",
                    "dictionarySource", "CODEBOOK",
                    "noMatchPolicy", "NULL"
                )
            )),
            "edges", List.of(Map.of("source", "transform_mapping", "target", "dict_gender"))
        ));

        DataModelDTO draft = service.createDraft(new DwdModelDraftRequest(
            "dwd_trade_order_gender_df",
            "交易",
            "ods.ods_codex_orders",
            "dwd.dwd_trade_order_gender_df",
            "TABLE",
            "order_id",
            null,
            null,
            List.of(
                new DwdModelDraftRequest.ColumnMappingRequest("order_id", "order_id", null, null, null, true, null, null, null, null, null, null),
                new DwdModelDraftRequest.ColumnMappingRequest("gender", "gender_name", "VARCHAR", "VARCHAR", null, false, null, null, null, null, null, null)
            ),
            "SPARK_GOVERNANCE",
            1,
            operatorGraph,
            "spark-default",
            "spark-small",
            "SPARK",
            null
        ));

        assertThat(draft.compiledSql())
            .contains("case when src.gender = 'M' then '男' when src.gender = 'F' then '女' else null end as gender_name");

        String previous = System.getProperty("onelake.dbt.projectDir");
        System.setProperty("onelake.dbt.projectDir", tempDir.toString());
        try {
            DwdModelCompileDTO compiled = service.compileArtifacts(MODEL_ID);
            String modelSql = Files.readString(tempDir.resolve(compiled.sqlPath()));

            assertThat(modelSql)
                .contains("case when src.gender = 'M' then '男' when src.gender = 'F' then '女' else null end as gender_name");
            assertThat(compiled.operatorGraph()).contains("standard.codebook_mapping", "core.gender", "2026.06");
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
    void publishValidatedModelEmitsPublishedEvent() {
        savedModel = validatedModel();

        DataModelDTO dto = service.publish(MODEL_ID, "发布治理表工厂模型");

        assertThat(dto.status()).isEqualTo("PUBLISHED");
        assertThat(savedModel.getStatus()).isEqualTo("PUBLISHED");
        verify(outboxPublisher).publish(
            eq(DomainEvents.MODELING_MODEL_PUBLISHED),
            eq(MODEL_ID.toString()),
            any(Map.class)
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void updateValidatedModelReturnsToDraftForRecompile() throws Exception {
        savedModel = validatedModel();
        mockCatalogAsset("ods.ods_codex_orders", "ODS", "交易", List.of(
            Map.of("name", "order_id", "type", "BIGINT"),
            Map.of("name", "status", "type", "VARCHAR")
        ));

        DataModelDTO dto = service.update(MODEL_ID, new DwdModelDraftRequest(
            "dwd_trade_order_df",
            "交易",
            "ods.ods_codex_orders",
            "dwd.dwd_trade_order_df",
            "TABLE",
            "order_id",
            null,
            null,
            List.of(
                new DwdModelDraftRequest.ColumnMappingRequest("order_id", "order_id", null, null, null, true, null, null, null, null, null, null),
                new DwdModelDraftRequest.ColumnMappingRequest("status", "order_status", null, null, null, false, null, null, null, null, null, null)
            )
        ));

        assertThat(dto.status()).isEqualTo("DRAFT");
        assertThat(savedModel.getStatus()).isEqualTo("DRAFT");
        assertThat(savedMappings)
            .extracting(DataModelColumnMapping::getTargetColumn)
            .containsExactly("order_id", "order_status");
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
            eq("onelake_pipeline_run"),
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
            .contains("            assertion_sql: \"select * from __ONELAKE_TEMPLATE__ where amount < 0\"")
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
            eq("onelake_pipeline_run"),
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
            eq("onelake_pipeline_run"),
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
                .contains("            assertion_sql: \"select * from __ONELAKE_TEMPLATE__ where amount < 0\"");
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
            eq("onelake_pipeline_run"),
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
            assertThat(modelSql).contains("where src.updated_at > (select coalesce(max(updated_at), timestamp '1970-01-01 00:00:00') from {{ this }})");
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
        mockBuiltInOperator("transform.spark_sql", "TRANSFORM");
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
        manifest.put("compileTarget", "SPARK");
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
        model.setDagsterJob("onelake_pipeline_run");
        model.setArtifactPath("models/intermediate/dwd_trade_order_df.sql");
        model.setOrchestrationDagId(UUID.fromString("33333333-3333-3333-3333-333333333333"));
        model.setResourceGroup("spark-default");
        model.setComputeProfile("spark-small");
        model.setEngine("SPARK");
        model.setOwnerId(USER_ID);
        model.setOwnerName("dev");
        return model;
    }
}
