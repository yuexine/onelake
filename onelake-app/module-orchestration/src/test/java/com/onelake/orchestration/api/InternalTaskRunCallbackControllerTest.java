package com.onelake.orchestration.api;

import com.onelake.common.exception.GlobalExceptionHandler;
import com.onelake.common.security.InternalApiTokenFilter;
import com.onelake.orchestration.domain.enums.TaskRunStatus;
import com.onelake.orchestration.dto.TaskRunCallbackResult;
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

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        InternalTaskRunCallbackController controller =
                new InternalTaskRunCallbackController(orchestrationService);
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
}
