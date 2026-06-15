package com.onelake.dataservice.api;

import com.onelake.common.api.ApiResponse;
import com.onelake.dataservice.domain.entity.ApiDefinition;
import com.onelake.dataservice.service.DataServicePublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/dataservice/apis")
@RequiredArgsConstructor
public class DataServiceController {

    private final DataServicePublisher publisher;

    @PostMapping
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<ApiDefinition> create(@RequestBody ApiDefinition def) {
        return ApiResponse.ok(publisher.publish(def));
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<ApiDefinition> publish(@PathVariable UUID id) {
        ApiDefinition def = publisher.get(id);
        return ApiResponse.ok(publisher.publish(def));
    }

    @GetMapping("/{id}")
    public ApiResponse<ApiDefinition> get(@PathVariable UUID id) {
        return ApiResponse.ok(publisher.get(id));
    }

    @GetMapping
    public ApiResponse<List<ApiDefinition>> list() {
        return ApiResponse.ok(publisher.list());
    }

    @PostMapping("/{id}/offline")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<Void> offline(@PathVariable UUID id) {
        publisher.offline(id);
        return ApiResponse.ok();
    }
}
