package com.onelake.orchestration.api;

import com.onelake.orchestration.dto.ParamDTO;
import com.onelake.orchestration.dto.ParamReplaceRequest;
import com.onelake.orchestration.service.PipelineParamService;
import io.swagger.v3.oas.annotations.Operation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 参数管理路由、响应包装、权限与 Swagger 声明测试。 */
@ExtendWith(MockitoExtension.class)
class PipelineParamControllerTest {

    @Mock private PipelineParamService paramService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new PipelineParamController(paramService)).build();
    }

    @Test
    void exposesPipelineAndGlobalGetPutRoutes() throws Exception {
        UUID dagId = UUID.randomUUID();
        ParamDTO pipeline = dto("PIPELINE", null, "region", "us", "STRING");
        ParamDTO global = dto("GLOBAL", null, "region", "cn", "STRING");
        when(paramService.listPipelineParams(dagId)).thenReturn(List.of(pipeline));
        when(paramService.replacePipelineParams(eq(dagId), any(ParamReplaceRequest.class)))
                .thenReturn(List.of(pipeline));
        when(paramService.listGlobalParams()).thenReturn(List.of(global));
        when(paramService.replaceGlobalParams(anyList())).thenReturn(List.of(global));
        String pipelinePath = "/api/v1/orchestration/pipelines/" + dagId + "/params";

        mockMvc.perform(get(pipelinePath))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].scope").value("PIPELINE"))
                .andExpect(jsonPath("$.data[0].valueType").value("STRING"));
        mockMvc.perform(put(pipelinePath)
                        .contentType("application/json")
                        .content("""
                                {"scope":"PIPELINE","params":[
                                  {"paramKey":"region","paramValue":"us","valueType":"STRING"}
                                ]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].paramValue").value("us"));
        mockMvc.perform(get("/api/v1/orchestration/params/global"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].scope").value("GLOBAL"));
        mockMvc.perform(put("/api/v1/orchestration/params/global")
                        .contentType("application/json")
                        .content("[]"))
                .andExpect(status().isOk());
    }

    @Test
    void allEndpointsRequireDeRoleAndDocumentOperation() throws Exception {
        Method[] methods = {
                PipelineParamController.class.getMethod("listPipelineParams", UUID.class),
                PipelineParamController.class.getMethod(
                        "replacePipelineParams", UUID.class, ParamReplaceRequest.class),
                PipelineParamController.class.getMethod("listGlobalParams"),
                PipelineParamController.class.getMethod("replaceGlobalParams", List.class),
        };

        for (Method method : methods) {
            assertThat(method.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasRole('DE')");
            assertThat(method.getAnnotation(Operation.class)).isNotNull();
            assertThat(method.getAnnotation(Operation.class).summary()).isNotBlank();
        }
    }

    private ParamDTO dto(String scope, String taskKey, String key, String value, String type) {
        return new ParamDTO(UUID.randomUUID(), scope, null, taskKey, key, value, type, "说明", null);
    }
}
