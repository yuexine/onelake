package com.onelake.orchestration.api;

import com.onelake.common.api.ApiResponse;
import com.onelake.orchestration.dto.SensorReadinessDTO;
import com.onelake.orchestration.service.SensorReadinessService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Dagster 观测节点使用的内部就绪查询接口；由内部令牌过滤器保护。 */
@RestController
@RequestMapping("/api/v1/internal/orchestration/dagster")
@RequiredArgsConstructor
public class InternalSensorReadinessController {

    private final SensorReadinessService sensorReadinessService;

    @GetMapping("/asset-readiness")
    public ApiResponse<SensorReadinessDTO> readiness(
            @RequestParam UUID tenantId,
            @RequestParam String assetFqn,
            @RequestParam(required = false) String partition) {
        return ApiResponse.ok(sensorReadinessService.readiness(tenantId, assetFqn, partition));
    }
}
