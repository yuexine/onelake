package com.onelake.orchestration.api;

import com.onelake.common.exception.GlobalExceptionHandler;
import com.onelake.common.util.JsonUtil;
import com.onelake.orchestration.dto.BackfillDTO;
import com.onelake.orchestration.dto.BackfillRunDTO;
import com.onelake.orchestration.dto.CreateBackfillRequest;
import com.onelake.orchestration.service.BackfillDispatcher;
import com.onelake.orchestration.service.BackfillService;
import io.swagger.v3.oas.annotations.Operation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class BackfillControllerTest {

    private static final UUID DAG_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID BACKFILL_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final Instant RANGE_START = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant RANGE_END = Instant.parse("2026-01-03T00:00:00Z");

    @Mock
    private BackfillService backfillService;

    @Mock
    private BackfillDispatcher backfillDispatcher;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(new BackfillController(backfillService, backfillDispatcher))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(JsonUtil.mapper()))
                .build();
    }

    @Test
    void createBackfillStartsDispatchAndReturnsProgressShape() throws Exception {
        BackfillDTO created = backfillDTO("QUEUED", 3, 0, 0);
        when(backfillService.createBackfill(eq(DAG_ID), eq(RANGE_START), eq(RANGE_END), eq("DAY"), eq(2)))
                .thenReturn(created);

        mockMvc.perform(post("/api/v1/orchestration/pipelines/" + DAG_ID + "/backfill")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "rangeStart": "2026-01-01T00:00:00Z",
                                  "rangeEnd": "2026-01-03T00:00:00Z",
                                  "grain": "DAY",
                                  "maxParallel": 2
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(BACKFILL_ID.toString()))
                .andExpect(jsonPath("$.data.dag_id").value(DAG_ID.toString()))
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.succeeded").value(0))
                .andExpect(jsonPath("$.data.failed").value(0))
                .andExpect(jsonPath("$.data.max_parallel").value(2))
                .andExpect(jsonPath("$.data.range.start").value("2026-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.data.range.end").value("2026-01-03T00:00:00Z"))
                .andExpect(jsonPath("$.data.grain").value("DAY"))
                .andExpect(jsonPath("$.data.runs[0].logical_date").value("2026-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.data.runs[0].status").value("QUEUED"))
                .andExpect(jsonPath("$.data.totalRuns").doesNotExist())
                .andExpect(jsonPath("$.data.maxParallel").doesNotExist());

        verify(backfillService).createBackfill(DAG_ID, RANGE_START, RANGE_END, "DAY", 2);
        verify(backfillDispatcher).dispatchNow(BACKFILL_ID);
        verify(backfillService, never()).dispatchBackfill(BACKFILL_ID);
        verify(backfillService, never()).getBackfill(BACKFILL_ID);
    }

    @Test
    void allEndpointsUseDeOpsAuthorizationAndSwaggerOperation() throws Exception {
        assertEndpointMetadata("create", UUID.class, CreateBackfillRequest.class);
        assertEndpointMetadata("get", UUID.class);
        assertEndpointMetadata("list", UUID.class);
        assertEndpointMetadata("cancel", UUID.class);
    }

    private void assertEndpointMetadata(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = BackfillController.class.getDeclaredMethod(methodName, parameterTypes);
        assertThat(method.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAnyRole('DE','OPS')");
        assertThat(method.getAnnotation(Operation.class)).isNotNull();
    }

    private BackfillDTO backfillDTO(String status, int total, int succeeded, int failed) {
        BackfillRunDTO run = new BackfillRunDTO(
                UUID.fromString("55555555-5555-5555-5555-555555555555"),
                null,
                RANGE_START,
                RANGE_START,
                RANGE_START.plus(java.time.Duration.ofDays(1)),
                "QUEUED",
                null);
        return new BackfillDTO(
                BACKFILL_ID,
                DAG_ID,
                status,
                total,
                succeeded,
                failed,
                2,
                new BackfillDTO.Range(RANGE_START, RANGE_END),
                "DAY",
                RANGE_START,
                RANGE_START,
                List.of(run));
    }
}
