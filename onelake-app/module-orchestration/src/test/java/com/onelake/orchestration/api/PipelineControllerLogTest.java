package com.onelake.orchestration.api;

import com.onelake.common.exception.GlobalExceptionHandler;
import com.onelake.orchestration.domain.enums.RunEnvironment;
import com.onelake.orchestration.domain.enums.TriggerType;
import com.onelake.orchestration.dto.PipelineCompilePreview;
import com.onelake.orchestration.service.OrchestrationService;
import com.onelake.orchestration.service.PipelineService;
import com.onelake.orchestration.service.RunContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PipelineControllerLogTest {

    private static final UUID DAG_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID RUN_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String TASK_KEY = "spark_node";
    private static final String LOG_PATH =
            "/api/v1/orchestration/pipelines/" + DAG_ID + "/runs/" + RUN_ID + "/tasks/" + TASK_KEY + "/log";

    @Mock
    private PipelineService pipelineService;

    @Mock
    private OrchestrationService orchestrationService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new PipelineController(pipelineService, orchestrationService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void streamsTailLog() throws Exception {
        byte[] body = "line-2\nline-3\n".getBytes(StandardCharsets.UTF_8);
        when(orchestrationService.readTaskRunLog(eq(DAG_ID), eq(RUN_ID), eq(TASK_KEY), eq(2)))
                .thenReturn(new OrchestrationService.TaskRunLogResource(
                        "tenant/run/spark_node/latest.log",
                        "spark_node.log",
                        body.length,
                        new ByteArrayInputStream(body)));

        MvcResult result = mockMvc.perform(get(LOG_PATH).param("tail", "2"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString("text/plain")))
                .andExpect(content().string("line-2\nline-3\n"));

        verify(orchestrationService).readTaskRunLog(DAG_ID, RUN_ID, TASK_KEY, 2);
    }

    @Test
    void downloadAddsContentDisposition() throws Exception {
        byte[] body = "full log\n".getBytes(StandardCharsets.UTF_8);
        when(orchestrationService.readTaskRunLog(eq(DAG_ID), eq(RUN_ID), eq(TASK_KEY), eq(null)))
                .thenReturn(new OrchestrationService.TaskRunLogResource(
                        "tenant/run/spark_node/latest.log",
                        "spark_node.log",
                        body.length,
                        new ByteArrayInputStream(body)));

        MvcResult result = mockMvc.perform(get(LOG_PATH).param("download", "true"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("attachment")))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("spark_node.log")))
                .andExpect(content().string("full log\n"));
    }

    @Test
    void manualTriggerPassesDevEnvironment() throws Exception {
        when(orchestrationService.triggerPipelineRun(
                eq(DAG_ID), eq(TriggerType.MANUAL), any(RunContext.class),
                isNull(), eq(RunEnvironment.DEV)))
                .thenReturn(RUN_ID);

        mockMvc.perform(post("/api/v1/orchestration/pipelines/{dagId}/trigger", DAG_ID)
                        .param("env", "DEV"))
                .andExpect(status().isOk());

        verify(orchestrationService).triggerPipelineRun(
                eq(DAG_ID), eq(TriggerType.MANUAL), any(RunContext.class),
                isNull(), eq(RunEnvironment.DEV));
    }

    @Test
    void manualTriggerDefaultsToProdEnvironment() throws Exception {
        when(orchestrationService.triggerPipelineRun(
                eq(DAG_ID), eq(TriggerType.MANUAL), any(RunContext.class),
                isNull(), eq(RunEnvironment.PROD)))
                .thenReturn(RUN_ID);

        mockMvc.perform(post("/api/v1/orchestration/pipelines/{dagId}/trigger", DAG_ID))
                .andExpect(status().isOk());

        verify(orchestrationService).triggerPipelineRun(
                eq(DAG_ID), eq(TriggerType.MANUAL), any(RunContext.class),
                isNull(), eq(RunEnvironment.PROD));
    }

    @Test
    void returnsNodeSqlCompilePreview() throws Exception {
        String sql = "CREATE OR REPLACE TABLE dwd.orders AS SELECT * FROM ods.orders";
        when(pipelineService.compilePreview(DAG_ID)).thenReturn(new PipelineCompilePreview(
                DAG_ID,
                true,
                List.of(new PipelineCompilePreview.NodeSqlPreview(
                        UUID.randomUUID(),
                        TASK_KEY,
                        "SPARK_SQL",
                        "transform.spark_sql",
                        "1.0.0",
                        "SPARK_SQL",
                        sql,
                        true,
                        true,
                        null)),
                List.of()));

        mockMvc.perform(get(
                        "/api/v1/orchestration/pipelines/{dagId}/compile-preview", DAG_ID))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"taskKey\":\"spark_node\"")))
                .andExpect(content().string(containsString("\"operatorVersion\":\"1.0.0\"")))
                .andExpect(content().string(containsString("CREATE OR REPLACE TABLE dwd.orders")));

        verify(pipelineService).compilePreview(DAG_ID);
    }
}
