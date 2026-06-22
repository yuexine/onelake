package com.onelake.common.task;

import com.onelake.common.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tasks")
@RequiredArgsConstructor
public class RunningTaskController {

    private final RunningTaskService service;

    @GetMapping("/running")
    public ApiResponse<List<RunningTaskDTO>> running(
        @RequestParam(defaultValue = "true") boolean includeRecent,
        @RequestParam(defaultValue = "20") int limit
    ) {
        return ApiResponse.ok(service.listRunning(includeRecent, limit));
    }

    @PostMapping("/{id}/dismiss")
    public ApiResponse<RunningTaskDTO> dismiss(@PathVariable UUID id) {
        return ApiResponse.ok(service.dismiss(id));
    }
}
