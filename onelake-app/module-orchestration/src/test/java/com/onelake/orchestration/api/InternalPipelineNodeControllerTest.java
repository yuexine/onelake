package com.onelake.orchestration.api;

import com.onelake.common.exception.GlobalExceptionHandler;
import com.onelake.common.security.InternalApiTokenFilter;
import com.onelake.orchestration.domain.enums.DagStatus;
import com.onelake.orchestration.dto.SubPipelineRunResult;
import com.onelake.orchestration.service.PipelineNodeExecutionService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class InternalPipelineNodeControllerTest {

    @Mock private PipelineNodeExecutionService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(new InternalPipelineNodeController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .addFilters(new InternalApiTokenFilter("secret-token"))
                .build();
    }

    @Test
    void validInternalTokenCanTriggerSubPipeline() throws Exception {
        UUID runId = UUID.randomUUID();
        when(service.triggerSubPipeline(any()))
                .thenReturn(new SubPipelineRunResult(runId, DagStatus.QUEUED));

        mockMvc.perform(post("/api/v1/internal/orchestration/dagster/sub-pipelines/trigger")
                        .header(InternalApiTokenFilter.INTERNAL_TOKEN_HEADER, "secret-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentRunId\":\"00000000-0000-0000-0000-000000000001\","
                                + "\"taskKey\":\"child\","
                                + "\"subDagId\":\"00000000-0000-0000-0000-000000000002\","
                                + "\"attempt\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runId").value(runId.toString()))
                .andExpect(jsonPath("$.data.status").value("QUEUED"));

        verify(service).triggerSubPipeline(any());
    }

    @Test
    void invalidTokenCannotSendNotification() throws Exception {
        mockMvc.perform(post("/api/v1/internal/orchestration/dagster/notifications")
                        .header(InternalApiTokenFilter.INTERNAL_TOKEN_HEADER, "wrong-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentRunId\":\"00000000-0000-0000-0000-000000000001\","
                                + "\"taskKey\":\"notify\",\"title\":\"done\","
                                + "\"message\":\"ok\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        verifyNoInteractions(service);
    }
}
