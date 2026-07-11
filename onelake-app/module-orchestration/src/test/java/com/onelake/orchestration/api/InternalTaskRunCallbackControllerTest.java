package com.onelake.orchestration.api;

import com.onelake.common.exception.GlobalExceptionHandler;
import com.onelake.common.security.InternalApiTokenFilter;
import com.onelake.common.util.JsonUtil;
import com.onelake.orchestration.domain.entity.Dag;
import com.onelake.orchestration.domain.entity.PipelineTask;
import com.onelake.orchestration.domain.entity.PipelineTaskEdge;
import com.onelake.orchestration.domain.enums.EdgeLayer;
import com.onelake.orchestration.domain.enums.TaskRunStatus;
import com.onelake.orchestration.dto.TaskRunCallbackResult;
import com.onelake.orchestration.dto.TaskConfigRenderResult;
import com.onelake.orchestration.repository.DagRepository;
import com.onelake.orchestration.repository.PipelineTaskEdgeRepository;
import com.onelake.orchestration.repository.PipelineTaskRepository;
import com.onelake.orchestration.service.OrchestrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.UUID;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class InternalTaskRunCallbackControllerTest {

    private static final UUID RUN_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String TASK_KEY = "spark_node";
    private static final String CALLBACK_PATH =
            "/api/v1/internal/orchestration/runs/" + RUN_ID + "/tasks/" + TASK_KEY + "/status";

    @Mock
    private OrchestrationService orchestrationService;

    @Mock
    private DagRepository dagRepository;

    @Mock
    private PipelineTaskRepository pipelineTaskRepository;

    @Mock
    private PipelineTaskEdgeRepository pipelineTaskEdgeRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        InternalTaskRunCallbackController controller =
                new InternalTaskRunCallbackController(orchestrationService, dagRepository,
                        pipelineTaskRepository, pipelineTaskEdgeRepository);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .addFilters(new InternalApiTokenFilter("secret-token"))
                .build();
    }

    @Test
    void callbackWithValidTokenAppliesStatus() throws Exception {
        when(orchestrationService.applyTaskRunCallback(eq(RUN_ID), eq(TASK_KEY), any()))
                .thenReturn(new TaskRunCallbackResult(true, TaskRunStatus.RUNNING));

        mockMvc.perform(post(CALLBACK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(InternalTaskRunCallbackController.INTERNAL_TOKEN_HEADER, "secret-token")
                        .content("{\"status\":\"RUNNING\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.applied").value(true))
                .andExpect(jsonPath("$.data.currentStatus").value("RUNNING"));

        verify(orchestrationService).applyTaskRunCallback(eq(RUN_ID), eq(TASK_KEY), any());
    }

    @Test
    void callbackWithInvalidTokenIsForbiddenBeforeBodyValidation() throws Exception {
        mockMvc.perform(post(CALLBACK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(InternalTaskRunCallbackController.INTERNAL_TOKEN_HEADER, "wrong-token")
                        .content("{\"status\":\"DONE\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        verifyNoInteractions(orchestrationService);
    }

    @Test
    void callbackWithoutStatusFailsValidation() throws Exception {
        mockMvc.perform(post(CALLBACK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(InternalTaskRunCallbackController.INTERNAL_TOKEN_HEADER, "secret-token")
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message", containsString("status")));

        verifyNoInteractions(orchestrationService);
    }

    @Test
    void callbackWithInvalidStatusEnumFailsValidation() throws Exception {
        mockMvc.perform(post(CALLBACK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(InternalTaskRunCallbackController.INTERNAL_TOKEN_HEADER, "secret-token")
                        .content("{\"status\":\"DONE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value("请求体格式错误"));

        verifyNoInteractions(orchestrationService);
    }

    @Test
    void renderConfigWithValidTokenReturnsFinalNodeConfig() throws Exception {
        String path = "/api/v1/internal/orchestration/runs/" + RUN_ID
                + "/tasks/quality_gate/render-config";
        when(orchestrationService.renderTaskConfig(
                eq(RUN_ID), eq("quality_gate"), any(), eq(List.of("extract"))))
                .thenReturn(new TaskConfigRenderResult(JsonUtil.mapper().readTree(
                        "{\"sql_or_script\":\"assert 88 >= 0\"}")));

        mockMvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(InternalTaskRunCallbackController.INTERNAL_TOKEN_HEADER, "secret-token")
                        .content("{\"config\":{\"sql_or_script\":"
                                + "\"assert ${upstream.extract.rowsWritten} >= 0\"},"
                                + "\"upstreamTaskKeys\":[\"extract\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.config.sql_or_script").value("assert 88 >= 0"));

        verify(orchestrationService).renderTaskConfig(
                eq(RUN_ID), eq("quality_gate"), any(), eq(List.of("extract")));
    }

    @Test
    void graphDefinitionsExposeOnlyPipelineEdgesForDagsterReload() throws Exception {
        UUID dagId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        Dag dag = new Dag();
        dag.setId(dagId);
        PipelineTask source = new PipelineTask();
        source.setTaskKey("sync_ref");
        PipelineTask target = new PipelineTask();
        target.setTaskKey("spark_sql");
        PipelineTaskEdge pipelineEdge = new PipelineTaskEdge();
        pipelineEdge.setSourceKey("sync_ref");
        pipelineEdge.setTargetKey("spark_sql");
        pipelineEdge.setEdgeLayer(EdgeLayer.PIPELINE);
        PipelineTaskEdge crossEngineEdge = new PipelineTaskEdge();
        crossEngineEdge.setSourceKey("sync_ref");
        crossEngineEdge.setTargetKey("spark_sql");
        crossEngineEdge.setEdgeLayer(EdgeLayer.CROSS_ENGINE);
        when(dagRepository.findAll()).thenReturn(List.of(dag));
        when(pipelineTaskRepository.findByDagIdOrderByCreatedAtAsc(dagId)).thenReturn(List.of(source, target));
        when(pipelineTaskEdgeRepository.findByDagId(dagId)).thenReturn(List.of(pipelineEdge, crossEngineEdge));

        mockMvc.perform(get("/api/v1/internal/orchestration/dagster/graph-definitions")
                        .header(InternalTaskRunCallbackController.INTERNAL_TOKEN_HEADER, "secret-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].pipeline_id").value(dagId.toString()))
                .andExpect(jsonPath("$.data[0].task_keys[0]").value("sync_ref"))
                .andExpect(jsonPath("$.data[0].edges.length()").value(1))
                .andExpect(jsonPath("$.data[0].edges[0].source_key").value("sync_ref"));
    }
}
