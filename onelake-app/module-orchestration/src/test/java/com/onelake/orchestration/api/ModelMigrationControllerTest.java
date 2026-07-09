package com.onelake.orchestration.api;

import com.onelake.orchestration.dto.ModelMigrationResult;
import com.onelake.orchestration.dto.ModelMigrationResult.MigrationItem;
import com.onelake.orchestration.service.ModelMigrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link ModelMigrationController} 的路由兼容性测试。
 *
 * <p>确保新模型迁移路径可用，并且旧 {@code /backfill} 路径在弃用期仍委托到同一服务方法。
 */
@ExtendWith(MockitoExtension.class)
class ModelMigrationControllerTest {

    @Mock
    private ModelMigrationService modelMigrationService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ModelMigrationController(modelMigrationService))
                .build();
    }

    @Test
    void dryRunUsesNewModelMigrationPath() throws Exception {
        UUID modelId = UUID.randomUUID();
        when(modelMigrationService.migrate(true)).thenReturn(result(true, modelId));

        mockMvc.perform(get("/api/v1/orchestration/pipelines/model-migration"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.dryRun").value(true))
                .andExpect(jsonPath("$.data.plannedItems[0].modelId").value(modelId.toString()));

        verify(modelMigrationService).migrate(true);
    }

    @Test
    void executeUsesNewModelMigrationPath() throws Exception {
        UUID modelId = UUID.randomUUID();
        when(modelMigrationService.migrate(false)).thenReturn(result(false, modelId));

        mockMvc.perform(post("/api/v1/orchestration/pipelines/model-migration")
                        .param("dryRun", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dryRun").value(false));

        verify(modelMigrationService).migrate(false);
    }

    @Test
    void dryRunKeepsLegacyBackfillPath() throws Exception {
        UUID modelId = UUID.randomUUID();
        when(modelMigrationService.migrate(true)).thenReturn(result(true, modelId));

        mockMvc.perform(get("/api/v1/orchestration/pipelines/backfill"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dryRun").value(true));

        verify(modelMigrationService).migrate(true);
    }

    @Test
    void executeKeepsLegacyBackfillPath() throws Exception {
        UUID modelId = UUID.randomUUID();
        when(modelMigrationService.migrate(false)).thenReturn(result(false, modelId));

        mockMvc.perform(post("/api/v1/orchestration/pipelines/backfill")
                        .param("dryRun", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dryRun").value(false));

        verify(modelMigrationService).migrate(false);
    }

    private ModelMigrationResult result(boolean dryRun, UUID modelId) {
        return new ModelMigrationResult(
                dryRun,
                1,
                List.of(new MigrationItem(modelId, "dwd_orders", "dwd_orders",
                        "iceberg.ods.orders", "iceberg.dwd.orders")),
                dryRun ? List.of() : List.of(UUID.randomUUID()),
                List.of(),
                List.of()
        );
    }
}
