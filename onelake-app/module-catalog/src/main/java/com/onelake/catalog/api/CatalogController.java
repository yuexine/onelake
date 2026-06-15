package com.onelake.catalog.api;

import com.onelake.catalog.domain.entity.Asset;
import com.onelake.catalog.service.CatalogService;
import com.onelake.catalog.service.CatalogSyncService;
import com.onelake.common.api.ApiResponse;
import com.onelake.common.context.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/catalog")
@RequiredArgsConstructor
public class CatalogController {

    private final CatalogService catalogService;
    private final CatalogSyncService syncService;

    @GetMapping("/assets/{id}")
    public ApiResponse<Asset> get(@PathVariable UUID id) {
        return ApiResponse.ok(catalogService.getAsset(id));
    }

    @GetMapping("/assets")
    public ApiResponse<List<Asset>> list(@RequestParam(required = false) String layer) {
        return ApiResponse.ok(catalogService.listByLayer(layer));
    }

    @GetMapping("/lineage/downstream")
    public ApiResponse<List<String>> downstream(@RequestParam String fqn) {
        return ApiResponse.ok(catalogService.downstream(TenantContext.getTenantId(), fqn));
    }

    @PostMapping("/sync")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> sync() {
        int n = syncService.syncTables();
        return ApiResponse.ok(Map.of("synced", n));
    }
}
