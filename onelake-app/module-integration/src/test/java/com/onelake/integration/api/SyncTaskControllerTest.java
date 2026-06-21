package com.onelake.integration.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onelake.integration.api.vo.CreateSyncTaskVO;
import com.onelake.integration.api.vo.UpdateSyncTaskVO;
import com.onelake.integration.dto.SyncRunLogDTO;
import com.onelake.integration.dto.SyncRunDTO;
import com.onelake.integration.dto.SyncTaskDTO;
import com.onelake.integration.dto.SyncTaskDryRunDTO;
import com.onelake.integration.service.SyncTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
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

class SyncTaskControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private SyncTaskService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(SyncTaskService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new SyncTaskController(service)).build();
    }

    @Test
    void createReturnsWrappedSyncTaskAndValidatesRequiredFields() throws Exception {
        UUID sourceId = UUID.randomUUID();
        CreateSyncTaskVO vo = createVo(sourceId, "orders-cdc");
        SyncTaskDTO dto = dto(UUID.randomUUID(), sourceId, "orders-cdc", "DRAFT");
        when(service.create(vo)).thenReturn(dto);

        mockMvc.perform(post("/api/v1/integration/sync-tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(vo)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("orders-cdc"))
            .andExpect(jsonPath("$.data.status").value("DRAFT"));

        mockMvc.perform(post("/api/v1/integration/sync-tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void listRoutesBindFiltersAndSourcePath() throws Exception {
        UUID sourceId = UUID.randomUUID();
        SyncTaskDTO dto = dto(UUID.randomUUID(), sourceId, "orders-cdc", "ENABLED");
        when(service.list(sourceId, "CDC", "ENABLED", "orders")).thenReturn(List.of(dto));
        when(service.listBySource(sourceId)).thenReturn(List.of(dto));

        mockMvc.perform(get("/api/v1/integration/sync-tasks")
                .param("sourceId", sourceId.toString())
                .param("mode", "CDC")
                .param("status", "ENABLED")
                .param("keyword", "orders"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].sourceId").value(sourceId.toString()));

        mockMvc.perform(get("/api/v1/integration/sync-tasks/by-source/{sourceId}", sourceId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].name").value("orders-cdc"));
    }

    @Test
    void getUpdateDeleteEnableAndDisableUsePathId() throws Exception {
        UUID id = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        UpdateSyncTaskVO update = new UpdateSyncTaskVO(
            "orders-full",
            "FULL",
            "public.orders",
            "dwd.orders",
            List.of(Map.of("source", "id", "target", "id")),
            "0 0 * * * ?",
            500,
            1,
            "conn-2"
        );
        when(service.get(id)).thenReturn(dto(id, sourceId, "orders-cdc", "DRAFT"));
        when(service.update(eq(id), eq(update))).thenReturn(dto(id, sourceId, "orders-full", "DRAFT"));
        when(service.enable(id)).thenReturn(dto(id, sourceId, "orders-full", "ENABLED"));
        when(service.disable(id)).thenReturn(dto(id, sourceId, "orders-full", "PAUSED"));

        mockMvc.perform(get("/api/v1/integration/sync-tasks/{id}", id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("orders-cdc"));

        mockMvc.perform(put("/api/v1/integration/sync-tasks/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(update)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("orders-full"));

        mockMvc.perform(post("/api/v1/integration/sync-tasks/{id}/enable", id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("ENABLED"));

        mockMvc.perform(post("/api/v1/integration/sync-tasks/{id}/disable", id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("PAUSED"));

        mockMvc.perform(delete("/api/v1/integration/sync-tasks/{id}", id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));

        verify(service).delete(id);
    }

    @Test
    void runTriggerReconcileAndRunsEndpointsReturnExpectedContracts() throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        SyncRunDTO run = new SyncRunDTO(
            runId,
            taskId,
            "987",
            "SUCCEEDED",
            120L,
            100L,
            null,
            null,
            null,
            2000L,
            50.0,
            Instant.now(),
            Instant.now()
        );
        when(service.trigger(taskId)).thenReturn(runId);
        when(service.getRun(runId)).thenReturn(run);
        when(service.cancelRun(runId)).thenReturn(run);
        when(service.logs(runId)).thenReturn(List.of(new SyncRunLogDTO(Instant.now(), "INFO", "job started")));
        when(service.runs(eq(taskId), any(PageRequest.class)))
            .thenReturn(new PageImpl<>(List.of(run), PageRequest.of(0, 10), 1));

        mockMvc.perform(post("/api/v1/integration/sync-tasks/{id}/run", taskId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.runId").value(runId.toString()));

        mockMvc.perform(post("/api/v1/integration/sync-tasks/{id}/trigger", taskId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.runId").value(runId.toString()));

        mockMvc.perform(post("/api/v1/integration/sync-tasks/runs/{runId}/reconcile", runId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(get("/api/v1/integration/sync-tasks/runs/{runId}", runId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.externalJobId").value("987"));

        mockMvc.perform(get("/api/v1/integration/sync-tasks/runs/{runId}/logs", runId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].message").value("job started"));

        mockMvc.perform(post("/api/v1/integration/sync-tasks/runs/{runId}/cancel", runId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("SUCCEEDED"));

        mockMvc.perform(get("/api/v1/integration/sync-tasks/{id}/runs", taskId)
                .param("page", "0")
                .param("size", "10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[0].throughputRows").value(50.0));

        verify(service).reconcile(runId);
    }

    @Test
    void dryRunEndpointsReturnChecks() throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        CreateSyncTaskVO vo = createVo(sourceId, "orders-cdc");
        SyncTaskDryRunDTO report = new SyncTaskDryRunDTO(true, List.of(
            new SyncTaskDryRunDTO.Check("source", "数据源", true, "数据源可读取")
        ));
        when(service.dryRun(vo)).thenReturn(report);
        when(service.dryRun(taskId)).thenReturn(report);

        mockMvc.perform(post("/api/v1/integration/sync-tasks/dry-run")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(vo)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.ready").value(true))
            .andExpect(jsonPath("$.data.checks[0].code").value("source"));

        mockMvc.perform(post("/api/v1/integration/sync-tasks/{id}/dry-run", taskId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.ready").value(true));
    }

    private CreateSyncTaskVO createVo(UUID sourceId, String name) {
        return new CreateSyncTaskVO(
            sourceId,
            name,
            "CDC",
            "public.orders",
            "ods.orders",
            List.of(Map.of("source", "id", "target", "id")),
            "0 */5 * * * ?",
            1000,
            5,
            "conn-1"
        );
    }

    private SyncTaskDTO dto(UUID id, UUID sourceId, String name, String status) {
        return new SyncTaskDTO(
            id,
            sourceId,
            "orders-db",
            name,
            "CDC",
            "public.orders",
            "ods.orders",
            List.of(Map.of("source", "id", "target", "id")),
            "0 */5 * * * ?",
            1000,
            5,
            status,
            "conn-1",
            Instant.now()
        );
    }
}
