package com.onelake.common.task;

import com.onelake.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "全局任务", description = "为前端全局任务条提供运行中和近期任务状态。")
public class RunningTaskController {

    private final RunningTaskService service;

    @Operation(
        summary = "查询运行中与近期任务",
        description = "用途：聚合后台长任务进度、状态和可取消入口。前端对接：TaskAPI.listRunning，由 useGlobalTasks 和 App 顶部任务条轮询使用。"
    )
    @GetMapping("/running")
    public ApiResponse<List<RunningTaskDTO>> running(
        @RequestParam(defaultValue = "true") boolean includeRecent,
        @RequestParam(defaultValue = "20") int limit
    ) {
        return ApiResponse.ok(service.listRunning(includeRecent, limit));
    }

    @Operation(
        summary = "忽略一个任务提示",
        description = "用途：将已完成、失败或用户已读任务从全局任务条中收起。前端对接：TaskAPI.dismiss，由 App 的任务条抽屉操作调用。"
    )
    @PostMapping("/{id}/dismiss")
    public ApiResponse<RunningTaskDTO> dismiss(@PathVariable UUID id) {
        return ApiResponse.ok(service.dismiss(id));
    }
}
