package com.onelake.catalog.api.sql;

import com.onelake.catalog.dto.sql.QueryTemplateDTO;
import com.onelake.catalog.dto.sql.QueryTemplateRenderRequest;
import com.onelake.catalog.dto.sql.QueryTemplateRenderResultDTO;
import com.onelake.catalog.dto.sql.QueryTemplateSaveRequest;
import com.onelake.catalog.service.sql.QueryTemplateService;
import com.onelake.common.api.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/lakehouse/sql/templates")
@RequiredArgsConstructor
public class QueryTemplateController {

    private final QueryTemplateService service;

    @GetMapping
    public ApiResponse<List<QueryTemplateDTO>> list() {
        return ApiResponse.ok(service.listTemplates());
    }

    @PostMapping
    public ApiResponse<QueryTemplateDTO> create(@Valid @RequestBody QueryTemplateSaveRequest request) {
        return ApiResponse.ok(service.saveTemplate(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<QueryTemplateDTO> update(
        @PathVariable UUID id,
        @Valid @RequestBody QueryTemplateSaveRequest request
    ) {
        return ApiResponse.ok(service.updateTemplate(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.deleteTemplate(id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{id}/render")
    public ApiResponse<QueryTemplateRenderResultDTO> render(
        @PathVariable UUID id,
        @RequestBody(required = false) QueryTemplateRenderRequest request
    ) {
        QueryTemplateRenderRequest req = request == null
            ? new QueryTemplateRenderRequest(java.util.Map.of())
            : request;
        return ApiResponse.ok(service.renderTemplate(id, req));
    }
}
