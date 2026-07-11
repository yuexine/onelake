package com.onelake.orchestration.api;

import com.onelake.common.exception.GlobalExceptionHandler;
import com.onelake.common.util.JsonUtil;
import com.onelake.orchestration.dto.PipelineVersionDetailDTO;
import com.onelake.orchestration.dto.PipelineVersionDiffDTO;
import com.onelake.orchestration.dto.PipelineVersionSummaryDTO;
import com.onelake.orchestration.service.PipelineVersionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PipelineVersionControllerTest {

    private static final UUID DAG_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID VERSION_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock private PipelineVersionService versionService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new PipelineVersionController(versionService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void listsVersionHistory() throws Exception {
        when(versionService.listVersions(DAG_ID)).thenReturn(List.of(summary()));

        mockMvc.perform(get("/api/v1/orchestration/pipelines/" + DAG_ID + "/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(VERSION_ID.toString()))
                .andExpect(jsonPath("$.data[0].version").value(1))
                .andExpect(jsonPath("$.data[0].checksum").value("checksum-1"));
    }

    @Test
    void returnsCompleteVersionSnapshot() throws Exception {
        PipelineVersionSummaryDTO summary = summary();
        when(versionService.getVersion(DAG_ID, 1)).thenReturn(new PipelineVersionDetailDTO(
                summary.id(), summary.dagId(), summary.version(), summary.checksum(), summary.status(),
                summary.note(), summary.publishedBy(), summary.publishedByName(), summary.createdAt(),
                JsonUtil.mapper().readTree("{\"tasks\":[{\"taskKey\":\"extract\"}],\"edges\":[],\"pipeline_params\":[]}")));

        mockMvc.perform(get("/api/v1/orchestration/pipelines/" + DAG_ID + "/versions/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.snapshot.tasks[0].taskKey").value("extract"))
                .andExpect(jsonPath("$.data.snapshot.pipeline_params").isArray());
    }

    @Test
    void returnsStructuredVersionDiff() throws Exception {
        PipelineVersionDiffDTO.CollectionDiff taskDiff = new PipelineVersionDiffDTO.CollectionDiff(
                List.of(new PipelineVersionDiffDTO.ItemDiff(
                        "load", null, JsonUtil.mapper().readTree("{\"taskKey\":\"load\"}"), List.of())),
                List.of(), List.of());
        PipelineVersionDiffDTO.CollectionDiff empty =
                new PipelineVersionDiffDTO.CollectionDiff(List.of(), List.of(), List.of());
        when(versionService.diff(DAG_ID, 1, 2))
                .thenReturn(new PipelineVersionDiffDTO(DAG_ID, 1, 2, taskDiff, empty, empty));

        mockMvc.perform(get("/api/v1/orchestration/pipelines/" + DAG_ID + "/versions/diff")
                        .param("from", "1")
                        .param("to", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fromVersion").value(1))
                .andExpect(jsonPath("$.data.toVersion").value(2))
                .andExpect(jsonPath("$.data.tasks.added[0].key").value("load"));
    }

    @Test
    void rollsBackVersionToDraft() throws Exception {
        mockMvc.perform(post("/api/v1/orchestration/pipelines/" + DAG_ID + "/versions/1/rollback"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        org.mockito.Mockito.verify(versionService).rollback(DAG_ID, 1);
    }

    private PipelineVersionSummaryDTO summary() {
        return new PipelineVersionSummaryDTO(
                VERSION_ID, DAG_ID, 1, "checksum-1", "PUBLISHED", null,
                null, "publisher", Instant.parse("2026-07-11T00:00:00Z"));
    }
}
