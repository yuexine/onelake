package com.onelake.integration.api;

import com.onelake.common.api.ApiResponse;
import com.onelake.integration.api.vo.ConnectivityResult;
import com.onelake.integration.api.vo.CreateDataSourceVO;
import com.onelake.integration.api.vo.UpdateDataSourceVO;
import com.onelake.integration.dto.DataSourceDTO;
import com.onelake.integration.service.DataSourceService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;

/**
 * 数据源 Controller（对应《技术初始化文档》§6.10 api 层）。
 * 路径与《详细功能清单》关键接口保持一致：/api/v1/integration/datasources
 */
@RestController
@RequestMapping("/api/v1/integration/datasources")
@RequiredArgsConstructor
public class DataSourceController {

    private final DataSourceService service;

    @PostMapping
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<DataSourceDTO> create(@Valid @RequestBody CreateDataSourceVO vo) {
        return ApiResponse.ok(service.create(vo));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<DataSourceDTO> update(@PathVariable UUID id,
                                             @RequestBody UpdateDataSourceVO vo) {
        return ApiResponse.ok(service.update(id, vo));
    }

    @GetMapping("/{id}")
    public ApiResponse<DataSourceDTO> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @GetMapping
    public ApiResponse<List<DataSourceDTO>> list(@RequestParam(required = false) String type) {
        return ApiResponse.ok(service.list(type));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/test")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<ConnectivityResult> test(@PathVariable UUID id) {
        return ApiResponse.ok(service.testConnectivity(id));
    }
}
