package com.onelake.orchestration.api;

import com.onelake.common.api.ApiResponse;
import com.onelake.orchestration.dto.RuntimeContractDTO;
import com.onelake.orchestration.service.RuntimeContractService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/orchestration/runtime-contracts")
@RequiredArgsConstructor
@Tag(name = "编排运行契约", description = "Spark 编译目标与 Dagster 运行态接入边界。")
public class RuntimeContractController {

    private final RuntimeContractService service;

    @Operation(
        summary = "查询运行契约",
        description = "用途：返回 Spark compileTarget 的 Manifest 支持、图级执行支持和 Dagster job 可用性，避免前端误放开未接入运行态的能力。"
    )
    @GetMapping
    public ApiResponse<List<RuntimeContractDTO>> list() {
        return ApiResponse.ok(service.listRuntimeContracts());
    }
}
