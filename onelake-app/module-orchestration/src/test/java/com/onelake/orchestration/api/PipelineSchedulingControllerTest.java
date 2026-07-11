package com.onelake.orchestration.api;

import com.onelake.orchestration.dto.DagSchedulingDTO;
import com.onelake.orchestration.dto.UpdateDagSchedulingRequest;
import com.onelake.orchestration.service.PipelineSchedulingService;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 调度配置路由、请求投影与 DE 权限声明测试。 */
@ExtendWith(MockitoExtension.class)
class PipelineSchedulingControllerTest {

    @Mock private PipelineSchedulingService schedulingService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new PipelineSchedulingController(schedulingService))
                .build();
    }

    @Test
    void exposesSchedulingReadWriteAndCalendarList() throws Exception {
        UUID dagId = UUID.randomUUID();
        DagSchedulingDTO dto = new DagSchedulingDTO(
                dagId, "Asia/Shanghai", true, 2, 9, "DRY_RUN",
                "FIRE_ONCE", 1440, 60, 120, 3, 30, null,
                Instant.parse("2026-07-10T00:00:00Z"), null);
        when(schedulingService.getScheduling(dagId)).thenReturn(dto);
        when(schedulingService.updateScheduling(eq(dagId), any())).thenReturn(dto);
        when(schedulingService.listCalendars()).thenReturn(List.of());
        when(schedulingService.listScheduleWaits(dagId)).thenReturn(List.of());
        String path = "/api/v1/orchestration/pipelines/" + dagId + "/scheduling";

        mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scheduleMode").value("DRY_RUN"));
        mockMvc.perform(put(path)
                        .contentType("application/json")
                        .content("""
                                {"timezone":"Asia/Shanghai","catchup":true,
                                 "maxActiveRuns":2,"priority":9,"scheduleMode":"DRY_RUN",
                                 "misfirePolicy":"FIRE_ONCE","dependencyWaitTimeoutMinutes":1440,
                                 "slaMinutes":60,"timeoutMinutes":120,
                                 "runRetryCount":3,"runRetryIntervalSeconds":30}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.maxActiveRuns").value(2))
                .andExpect(jsonPath("$.data.misfirePolicy").value("FIRE_ONCE"))
                .andExpect(jsonPath("$.data.runRetryCount").value(3));
        mockMvc.perform(get("/api/v1/orchestration/schedule-calendars"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
        mockMvc.perform(get("/api/v1/orchestration/pipelines/" + dagId + "/schedule-waits"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void allEndpointsRequireDeRole() throws Exception {
        Method get = PipelineSchedulingController.class.getMethod("get", UUID.class);
        Method update = PipelineSchedulingController.class
                .getMethod("update", UUID.class, UpdateDagSchedulingRequest.class);
        Method calendars = PipelineSchedulingController.class.getMethod("calendars");
        Method waits = PipelineSchedulingController.class.getMethod("waits", UUID.class);

        assertThat(get.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasRole('DE')");
        assertThat(update.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasRole('DE')");
        assertThat(calendars.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasRole('DE')");
        assertThat(waits.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasRole('DE')");
    }
}
