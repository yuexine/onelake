package com.onelake.orchestration.api;

import com.onelake.orchestration.dto.PipelineDependencyDTO;
import com.onelake.orchestration.dto.PipelineDependencyRequest;
import com.onelake.orchestration.service.PipelineDependencyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 依赖管理路由、请求投影与 DE 权限声明测试。 */
@ExtendWith(MockitoExtension.class)
class PipelineDependencyControllerTest {

    @Mock private PipelineDependencyService dependencyService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new PipelineDependencyController(dependencyService))
                .build();
    }

    @Test
    void exposesGetPostAndDeleteDependencies() throws Exception {
        UUID downstreamDagId = UUID.randomUUID();
        UUID upstreamDagId = UUID.randomUUID();
        UUID dependencyId = UUID.randomUUID();
        PipelineDependencyDTO dto = new PipelineDependencyDTO(
                dependencyId, downstreamDagId, upstreamDagId,
                "CROSS_CYCLE", "DAY", -1, true,
                Instant.parse("2026-07-10T12:00:00Z"));
        when(dependencyService.listDependencies(downstreamDagId))
                .thenReturn(List.of(dto));
        when(dependencyService.listEnabledDependencies()).thenReturn(List.of(dto));
        when(dependencyService.createDependency(
                org.mockito.ArgumentMatchers.eq(downstreamDagId), any()))
                .thenReturn(dto);
        String path = "/api/v1/orchestration/pipelines/" + downstreamDagId + "/dependencies";

        mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].upstreamDagId")
                        .value(upstreamDagId.toString()));
        mockMvc.perform(post(path)
                        .contentType("application/json")
                        .content("""
                                {"upstreamDagId":"%s","dependencyType":"CROSS_CYCLE",
                                 "offsetGrain":"DAY","offsetN":-1}
                                """.formatted(upstreamDagId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.offsetN").value(-1));
        mockMvc.perform(delete(path + "/" + dependencyId))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/orchestration/pipeline-dependencies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(dependencyId.toString()));

        verify(dependencyService).createDependency(
                org.mockito.ArgumentMatchers.eq(downstreamDagId),
                org.mockito.ArgumentMatchers.eq(new PipelineDependencyRequest(
                        upstreamDagId, "CROSS_CYCLE", "DAY", -1)));
        verify(dependencyService).deleteDependency(downstreamDagId, dependencyId);
    }

    @Test
    void bothEndpointsRequireDeRole() throws Exception {
        Method list = PipelineDependencyController.class
                .getMethod("list", UUID.class);
        Method create = PipelineDependencyController.class
                .getMethod("create", UUID.class, PipelineDependencyRequest.class);
        Method delete = PipelineDependencyController.class
                .getMethod("delete", UUID.class, UUID.class);
        Method listEnabled = PipelineDependencyController.class
                .getMethod("listEnabled");

        assertThat(list.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("hasRole('DE')");
        assertThat(create.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("hasRole('DE')");
        assertThat(delete.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("hasRole('DE')");
        assertThat(listEnabled.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("hasRole('DE')");
    }
}
