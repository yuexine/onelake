package com.onelake.common.system.api;

import com.onelake.common.api.ApiResponse;
import com.onelake.common.system.dto.ProjectOptionDTO;
import com.onelake.common.system.dto.SystemContextDTO;
import com.onelake.common.system.service.SystemContextService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/system")
@RequiredArgsConstructor
@Tag(name = "系统上下文", description = "为前端控制台提供当前租户、项目、用户与角色上下文。")
public class SystemContextController {

    private final SystemContextService service;

    @Operation(
        summary = "获取当前系统上下文",
        description = "用途：返回当前登录用户所在租户、可选项目、用户标识和角色。前端对接：SystemAPI.context，当前 DatasourceList 用于判断上下文与权限提示，也是控制台初始化的基础接口。"
    )
    @GetMapping("/context")
    public ApiResponse<SystemContextDTO> context() {
        return ApiResponse.ok(service.currentContext());
    }

    @Operation(
        summary = "获取当前租户下的项目选项",
        description = "用途：返回项目下拉候选列表。前端对接：SystemAPI.projects 已封装，当前页面未直接调用，供后续项目切换或资源归属选择使用。"
    )
    @GetMapping("/projects")
    public ApiResponse<List<ProjectOptionDTO>> projects() {
        return ApiResponse.ok(service.projects());
    }
}
