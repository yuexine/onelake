package com.onelake.orchestration.api;

import com.onelake.common.exception.GlobalExceptionHandler;
import com.onelake.common.util.JsonUtil;
import com.onelake.orchestration.dto.PipelineSubscriptionDTO;
import com.onelake.orchestration.dto.PipelineSubscriptionRequest;
import com.onelake.orchestration.service.PipelineSubscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PipelineSubscriptionControllerTest {

    private static final UUID DAG_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SUBSCRIPTION_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock private PipelineSubscriptionService subscriptionService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new PipelineSubscriptionController(subscriptionService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void listsSubscriptions() throws Exception {
        when(subscriptionService.list(DAG_ID)).thenReturn(List.of(subscription()));

        mockMvc.perform(get("/api/v1/orchestration/pipelines/" + DAG_ID + "/subscriptions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(SUBSCRIPTION_ID.toString()))
                .andExpect(jsonPath("$.data[0].condition")
                        .value("ON_UPDATE_AND_QUALITY_PASS"))
                .andExpect(jsonPath("$.data[0].freshnessPolicy").value("SAME_BATCH"));
    }

    @Test
    void createsSubscription() throws Exception {
        PipelineSubscriptionRequest request = new PipelineSubscriptionRequest(
                "PIPELINE", "44444444-4444-4444-4444-444444444444",
                "ON_UPDATE_AND_QUALITY_PASS", "SAME_BATCH");
        when(subscriptionService.create(any(), any())).thenReturn(subscription());

        mockMvc.perform(post("/api/v1/orchestration/pipelines/" + DAG_ID + "/subscriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.mapper().writeValueAsBytes(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sourceType").value("PIPELINE"));
    }

    @Test
    void deletesSubscription() throws Exception {
        mockMvc.perform(delete("/api/v1/orchestration/pipelines/" + DAG_ID
                        + "/subscriptions/" + SUBSCRIPTION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(subscriptionService).delete(DAG_ID, SUBSCRIPTION_ID);
    }

    private PipelineSubscriptionDTO subscription() {
        return new PipelineSubscriptionDTO(
                SUBSCRIPTION_ID,
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                DAG_ID,
                "PIPELINE",
                "44444444-4444-4444-4444-444444444444",
                "ON_UPDATE_AND_QUALITY_PASS",
                "SAME_BATCH",
                true,
                Instant.parse("2026-07-12T00:00:00Z"));
    }
}
