package com.onelake.common.system.api;

import com.onelake.common.api.ApiResponse;
import com.onelake.common.system.dto.ProjectOptionDTO;
import com.onelake.common.system.dto.SystemContextDTO;
import com.onelake.common.system.service.SystemContextService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/system")
@RequiredArgsConstructor
public class SystemContextController {

    private final SystemContextService service;

    @GetMapping("/context")
    public ApiResponse<SystemContextDTO> context() {
        return ApiResponse.ok(service.currentContext());
    }

    @GetMapping("/projects")
    public ApiResponse<List<ProjectOptionDTO>> projects() {
        return ApiResponse.ok(service.projects());
    }
}
