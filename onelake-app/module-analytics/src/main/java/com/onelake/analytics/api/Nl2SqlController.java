package com.onelake.analytics.api;

import com.onelake.analytics.dto.DatasetDTO;
import com.onelake.analytics.repository.DatasetRepository;
import com.onelake.analytics.service.Nl2SqlService;
import com.onelake.common.api.ApiResponse;
import com.onelake.common.exception.BizException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * NL2SQL Controller（P5-C 智能建数据集）。
 *
 * 端点：
 *   POST /api/v1/analytics/nl2sql
 *     body: { "asset_fqn": "...", "field_schema": [...], "question": "..." }
 *     resp: { "sql": "SELECT ..." }
 *
 * 前端在数据集编辑器中"自然语言建数据集"向导调用此端点，
 * 拿到 SQL 后用户可手动微调 → 提交 createDataset(sourceType=SQL)。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/analytics/nl2sql")
@RequiredArgsConstructor
@Tag(name = "数据分析-NL2SQL", description = "自然语言 → Trino SQL 智能生成（LLM）")
public class Nl2SqlController {

    private final Nl2SqlService service;
    private final DatasetRepository datasetRepo;

    @Operation(summary = "自然语言生成 Trino SQL")
    @PostMapping
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<Map<String, String>> generate(@RequestBody Map<String, Object> body) {
        String assetFqn = (String) body.get("asset_fqn");
        if (assetFqn == null || assetFqn.isBlank()) {
            return ApiResponse.fail(40000, "asset_fqn 必填");
        }
        String question = (String) body.get("question");
        if (question == null || question.isBlank()) {
            return ApiResponse.fail(40000, "question 必填");
        }

        // 可选：从已存在的 dataset_id 拉字段 schema 作为 context
        List<DatasetDTO.FieldSchema> fieldSchema = null;
        Object datasetIdObj = body.get("dataset_id");
        if (datasetIdObj != null && !datasetIdObj.toString().isBlank()) {
            fieldSchema = datasetRepo.findById(UUID.fromString(datasetIdObj.toString()))
                .<List<DatasetDTO.FieldSchema>>map(ds -> {
                    // 直接从 entity 字段解析（实际场景下 DatasetService 已有 toDto，这里简化）
                    return List.of();  // 占位：前端通常会直接传 field_schema
                })
                .orElse(null);
        }
        // 优先用 body 中传入的 field_schema
        Object rawSchema = body.get("field_schema");
        if (rawSchema instanceof List<?> list && !list.isEmpty()) {
            fieldSchema = list.stream()
                .filter(o -> o instanceof java.util.Map<?, ?>)
                .map(o -> {
                    java.util.Map<?, ?> m = (java.util.Map<?, ?>) o;
                    return DatasetDTO.FieldSchema.builder()
                        .name(String.valueOf(m.get("name")))
                        .type(String.valueOf(m.get("type")))
                        .classification(m.get("classification") == null ? null
                            : String.valueOf(m.get("classification")))
                        .build();
                })
                .toList();
        }

        String sql = service.generateSql(assetFqn, fieldSchema, question);
        return ApiResponse.ok(Map.of("sql", sql));
    }
}
