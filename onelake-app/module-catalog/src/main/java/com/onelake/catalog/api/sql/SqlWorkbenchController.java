package com.onelake.catalog.api.sql;

import com.onelake.catalog.dto.sql.SavedQueryDTO;
import com.onelake.catalog.dto.sql.SqlEstimateDTO;
import com.onelake.catalog.dto.sql.SqlExecuteRequest;
import com.onelake.catalog.dto.sql.SqlExecuteResultDTO;
import com.onelake.catalog.dto.sql.SqlQueryHistoryDTO;
import com.onelake.catalog.dto.sql.SqlSaveQueryRequest;
import com.onelake.catalog.service.sql.SqlWorkbenchService;
import com.onelake.common.api.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/lakehouse/sql")
@RequiredArgsConstructor
public class SqlWorkbenchController {

    private final SqlWorkbenchService service;

    @PostMapping("/estimate")
    public ApiResponse<SqlEstimateDTO> estimate(@Valid @RequestBody SqlExecuteRequest request) {
        return ApiResponse.ok(service.estimate(request));
    }

    @PostMapping("/execute")
    public ApiResponse<SqlExecuteResultDTO> execute(@Valid @RequestBody SqlExecuteRequest request) {
        return ApiResponse.ok(service.execute(request));
    }

    @PostMapping("/queries")
    public ApiResponse<SqlExecuteResultDTO> submit(@Valid @RequestBody SqlExecuteRequest request) {
        return ApiResponse.ok(service.submit(request));
    }

    @GetMapping("/queries/{id}")
    public ApiResponse<SqlExecuteResultDTO> query(@PathVariable UUID id) {
        return ApiResponse.ok(service.query(id));
    }

    @PostMapping("/queries/{id}/cancel")
    public ApiResponse<SqlExecuteResultDTO> cancel(@PathVariable UUID id) {
        return ApiResponse.ok(service.cancel(id));
    }

    @GetMapping("/history")
    public ApiResponse<List<SqlQueryHistoryDTO>> history() {
        return ApiResponse.ok(service.history());
    }

    @GetMapping("/saved-queries")
    public ApiResponse<List<SavedQueryDTO>> savedQueries() {
        return ApiResponse.ok(service.savedQueries());
    }

    @PostMapping("/saved-queries")
    public ApiResponse<SavedQueryDTO> saveQuery(@Valid @RequestBody SqlSaveQueryRequest request) {
        return ApiResponse.ok(service.saveQuery(request));
    }
}
