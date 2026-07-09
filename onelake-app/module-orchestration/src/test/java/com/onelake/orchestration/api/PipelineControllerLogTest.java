package com.onelake.orchestration.api;

import com.onelake.common.exception.GlobalExceptionHandler;
import com.onelake.orchestration.service.OrchestrationService;
import com.onelake.orchestration.service.PipelineService;
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
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
}
