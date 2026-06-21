package com.onelake.integration.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onelake.common.util.JsonUtil;
import com.onelake.integration.api.vo.ConnectivityResult;
import com.onelake.integration.api.vo.CreateDataSourceVO;
import com.onelake.integration.api.vo.DatabaseProbeResult;
import com.onelake.integration.api.vo.ProbeDatabasesVO;
import com.onelake.integration.api.vo.TestDataSourceVO;
import com.onelake.integration.api.vo.UpdateDataSourceVO;
import com.onelake.integration.dto.DataSourceDTO;
import com.onelake.integration.dto.DiscoveredColumnDTO;
import com.onelake.integration.dto.AirbyteConnectorDefinitionDTO;
import com.onelake.integration.dto.AirbyteConnectorSpecDTO;
import com.onelake.integration.service.DataSourceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DataSourceControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private DataSourceService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(DataSourceService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new DataSourceController(service)).build();
    }

    @Test
    void createReturnsWrappedDatasource() throws Exception {
        CreateDataSourceVO vo = new CreateDataSourceVO(
            "orders-db",
            "POSTGRES",
            Map.of("host", "db.internal", "port", 5432),
            "secret-ref",
            "DIRECT",
            "PROD",
            null
        );
        DataSourceDTO dto = dto(UUID.randomUUID(), "orders-db");
        when(service.create(vo)).thenReturn(dto);

        mockMvc.perform(post("/api/v1/integration/datasources")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(vo)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.message").value("ok"))
            .andExpect(jsonPath("$.data.id").value(dto.id().toString()))
            .andExpect(jsonPath("$.data.name").value("orders-db"));
    }

    @Test
    void createValidatesRequiredFields() throws Exception {
        mockMvc.perform(post("/api/v1/integration/datasources")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void listBindsOptionalFilters() throws Exception {
        DataSourceDTO dto = dto(UUID.randomUUID(), "orders-db");
        when(service.list("POSTGRES", "OK", "PROD", "orders")).thenReturn(List.of(dto));

        mockMvc.perform(get("/api/v1/integration/datasources")
                .param("type", "POSTGRES")
                .param("health", "OK")
                .param("envLevel", "PROD")
                .param("keyword", "orders"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].name").value("orders-db"));

        verify(service).list("POSTGRES", "OK", "PROD", "orders");
    }

    @Test
    void getUpdateDeleteAndConnectivityUsePathId() throws Exception {
        UUID id = UUID.randomUUID();
        UpdateDataSourceVO update = new UpdateDataSourceVO(
            "orders-prod",
            Map.of("host", "prod-db"),
            "secret-new",
            "VPC",
            "PROD",
            null
        );
        ConnectivityResult connectivity = new ConnectivityResult(
            true,
            null,
            "连通",
            10L,
            Instant.now(),
            Map.of("host", "prod-db")
        );
        when(service.get(id)).thenReturn(dto(id, "orders-db"));
        when(service.update(eq(id), eq(update))).thenReturn(dto(id, "orders-prod"));
        when(service.testConnectivity(id)).thenReturn(connectivity);

        mockMvc.perform(get("/api/v1/integration/datasources/{id}", id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("orders-db"));

        mockMvc.perform(put("/api/v1/integration/datasources/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(update)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("orders-prod"));

        mockMvc.perform(post("/api/v1/integration/datasources/{id}/test", id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.ok").value(true));

        mockMvc.perform(delete("/api/v1/integration/datasources/{id}", id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));

        verify(service).delete(id);
    }

    @Test
    void probeDatabasesReturnsWrappedOptions() throws Exception {
        ProbeDatabasesVO vo = new ProbeDatabasesVO(
            "MYSQL",
            Map.of("host", "db.internal", "port", 3306, "username", "reader"),
            "DIRECT"
        );
        when(service.probeDatabases(vo)).thenReturn(new DatabaseProbeResult(List.of("orders", "inventory"), true, "ok"));

        mockMvc.perform(post("/api/v1/integration/datasources/probe-databases")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(vo)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.databases[0]").value("orders"))
            .andExpect(jsonPath("$.data.manualAllowed").value(true));
    }

    @Test
    void discoveryEndpointsBindDatasourceIdAndObjectName() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.listSchemas(id)).thenReturn(List.of("public"));
        when(service.listTables(id, "public")).thenReturn(List.of("public.orders"));
        when(service.describeTable(id, "public.orders")).thenReturn(List.of(
            new DiscoveredColumnDTO("id", "bigint", false, true, 1)
        ));

        mockMvc.perform(get("/api/v1/integration/datasources/{id}/schemas", id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0]").value("public"));

        mockMvc.perform(get("/api/v1/integration/datasources/{id}/tables", id)
                .param("schema", "public"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0]").value("public.orders"));

        mockMvc.perform(get("/api/v1/integration/datasources/{id}/tables/{objectName}/columns", id, "public.orders"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].name").value("id"))
            .andExpect(jsonPath("$.data[0].primaryKey").value(true));
    }

    @Test
    void testConfigReturnsWrappedConnectivityResult() throws Exception {
        TestDataSourceVO vo = new TestDataSourceVO(
            "MYSQL",
            Map.of("host", "db.internal", "port", 3306, "dbName", "orders", "username", "reader"),
            "DIRECT"
        );
        ConnectivityResult connectivity = new ConnectivityResult(
            true,
            null,
            "连通",
            8L,
            Instant.now(),
            Map.of("host", "db.internal")
        );
        when(service.testConnectivity(vo)).thenReturn(connectivity);

        mockMvc.perform(post("/api/v1/integration/datasources/test-config")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(vo)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.ok").value(true))
            .andExpect(jsonPath("$.data.rttMillis").value(8));
    }

    @Test
    void airbyteConnectorEndpointsReturnDefinitionsAndSpec() throws Exception {
        AirbyteConnectorDefinitionDTO source = new AirbyteConnectorDefinitionDTO(
            "src-def-1",
            "Postgres",
            "airbyte/source-postgres",
            "1.0.0",
            "SOURCE"
        );
        AirbyteConnectorDefinitionDTO destination = new AirbyteConnectorDefinitionDTO(
            "dst-def-1",
            "S3",
            "airbyte/destination-s3",
            "1.0.0",
            "DESTINATION"
        );
        when(service.listAirbyteSourceDefinitions()).thenReturn(List.of(source));
        when(service.listAirbyteDestinationDefinitions()).thenReturn(List.of(destination));
        when(service.getAirbyteSourceDefinitionSpec("src-def-1")).thenReturn(new AirbyteConnectorSpecDTO(
            "src-def-1",
            "SOURCE",
            "https://docs.airbyte.com",
            JsonUtil.mapper().createObjectNode().put("title", "Postgres Source")
        ));
        when(service.getAirbyteDestinationDefinitionSpec("dst-def-1")).thenReturn(new AirbyteConnectorSpecDTO(
            "dst-def-1",
            "DESTINATION",
            "https://docs.airbyte.com",
            JsonUtil.mapper().createObjectNode().put("title", "S3 Destination")
        ));

        mockMvc.perform(get("/api/v1/integration/datasources/airbyte/source-definitions"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].id").value("src-def-1"))
            .andExpect(jsonPath("$.data[0].type").value("SOURCE"));

        mockMvc.perform(get("/api/v1/integration/datasources/airbyte/destination-definitions"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].id").value("dst-def-1"))
            .andExpect(jsonPath("$.data[0].type").value("DESTINATION"));

        mockMvc.perform(get("/api/v1/integration/datasources/airbyte/source-definitions/{definitionId}/spec", "src-def-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.definitionId").value("src-def-1"))
            .andExpect(jsonPath("$.data.connectionSpecification.title").value("Postgres Source"));

        mockMvc.perform(get("/api/v1/integration/datasources/airbyte/destination-definitions/{definitionId}/spec", "dst-def-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.definitionId").value("dst-def-1"))
            .andExpect(jsonPath("$.data.connectionSpecification.title").value("S3 Destination"));
    }

    private DataSourceDTO dto(UUID id, String name) {
        return new DataSourceDTO(
            id,
            UUID.randomUUID(),
            name,
            "POSTGRES",
            "db.internal",
            5432,
            "orders",
            "reader",
            "OK",
            null,
            null,
            "DIRECT",
            "PROD",
            "secret-ref",
            Instant.now(),
            Instant.now()
        );
    }
}
